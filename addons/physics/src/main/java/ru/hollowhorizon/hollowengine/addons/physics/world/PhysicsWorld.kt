package ru.hollowhorizon.hollowengine.addons.physics.world

import com.github.stephengold.joltjni.*
import net.minecraft.world.level.Level
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.addons.physics.ragdoll.RagdollInstance
import kotlin.math.ceil

/**
 * Jolt simulation for one level.
 */
class PhysicsWorld(val level: Level) : AutoCloseable {
    val system: PhysicsSystem = createSystem()
    private val tempAllocator = TempAllocatorImpl(TEMP_ALLOCATOR_BYTES)
    private val jobSystem = JobSystemThreadPool(Jolt.cMaxPhysicsJobs, Jolt.cMaxPhysicsBarriers, workerThreads())

    val bodies: BodyInterface by lazy { system.bodyInterface }
    val blocks = BlockColliders(this)

    private val liveRagdolls = LinkedHashMap<Any, RagdollInstance>()

    val ragdolls: Collection<RagdollInstance> get() = liveRagdolls.values

    private var steppedFrame = Long.MIN_VALUE
    private var now = 0.0

    fun ragdoll(key: Any, create: () -> RagdollInstance?): RagdollInstance? {
        val existing = liveRagdolls[key]
        if (existing != null) {
            existing.lastUsed = now
            return existing
        }

        val created = create() ?: return null
        created.lastUsed = now
        liveRagdolls[key] = created
        return created
    }

    fun forget(key: Any) {
        liveRagdolls.remove(key)?.close()
    }

    /**
     * Moves the simulation to this frame.
     *
     * [deltaTime] is the in-game time corresponding to this frame, so the simulation slows down at
     * `/tick rate` and stops at `/tick freeze`, just like the animation with which it is synchronized.
     */
    fun stepOnce(frame: Long, deltaTime: Float) {
        if (steppedFrame == frame) return
        steppedFrame = frame

        liveRagdolls.values.forEach(RagdollInstance::beforeStep)
        blocks.commit()

        val step = deltaTime.coerceIn(0f, MAX_STEP * MAX_COLLISION_STEPS)
        if (step <= 0f) return

        val collisionSteps = ceil(step / MAX_STEP).toInt().coerceIn(1, MAX_COLLISION_STEPS)
        system.update(step, collisionSteps, tempAllocator, jobSystem)
    }

    /**
     * World's own clock, operating at a rate of one client tick.
     */
    fun tick(deltaTime: Float) {
        now += deltaTime.toDouble()
        blocks.tick(deltaTime)
        reapIdleRagdolls()
    }

    private fun reapIdleRagdolls() {
        val iterator = liveRagdolls.entries.iterator()
        while (iterator.hasNext()) {
            val instance = iterator.next().value
            if (now - instance.lastUsed < IDLE_SECONDS) continue
            instance.close()
            iterator.remove()
        }
    }

    override fun close() {
        liveRagdolls.values.forEach(RagdollInstance::close)
        liveRagdolls.clear()
        blocks.close()
        system.removeAllBodies()
        system.destroyAllBodies()
        HollowEngine.LOGGER.debug("Physics world for {} closed", level.dimension().location())
    }

    companion object {
        /**
         * A system with this addon's two object layers: the blocks of the world, which never move, and
         * everything simulated, which collides with them.
         */
        fun createSystem(): PhysicsSystem {
            val layers = BroadPhaseLayerInterfaceTable(
                OBJECT_LAYERS, BROAD_PHASE_LAYERS
            ).mapObjectToBroadPhaseLayer(LAYER_STATIC, BROAD_PHASE_STATIC)
                .mapObjectToBroadPhaseLayer(LAYER_DYNAMIC, BROAD_PHASE_DYNAMIC)
            val pairFilter = ObjectLayerPairFilterTable(OBJECT_LAYERS).enableCollision(LAYER_DYNAMIC, LAYER_STATIC)
                .enableCollision(LAYER_DYNAMIC, LAYER_DYNAMIC)
            val broadPhaseFilter =
                ObjectVsBroadPhaseLayerFilterTable(layers, BROAD_PHASE_LAYERS, pairFilter, OBJECT_LAYERS)

            return PhysicsSystem().init(
                MAX_BODIES, 0, MAX_BODY_PAIRS, MAX_CONTACTS, layers, broadPhaseFilter, pairFilter
            ).apply { setGravity(Vec3(0f, GRAVITY, 0f)) }
        }

        const val LAYER_STATIC = 0
        const val LAYER_DYNAMIC = 1
        private const val OBJECT_LAYERS = 2

        private const val BROAD_PHASE_STATIC = 0
        private const val BROAD_PHASE_DYNAMIC = 1
        private const val BROAD_PHASE_LAYERS = 2

        private const val GRAVITY = -9.81f

        /** The longest one collision step may be: past this Jolt stops being stable. */
        private const val MAX_STEP = 1f / 60f
        private const val MAX_COLLISION_STEPS = 4
        private const val IDLE_SECONDS = 0.5

        private const val MAX_BODIES = 4096
        private const val MAX_BODY_PAIRS = 4096
        private const val MAX_CONTACTS = 2048
        private const val TEMP_ALLOCATOR_BYTES = 16 * 1024 * 1024

        private fun workerThreads(): Int = (Runtime.getRuntime().availableProcessors() / 4).coerceIn(1, 3)
    }
}
