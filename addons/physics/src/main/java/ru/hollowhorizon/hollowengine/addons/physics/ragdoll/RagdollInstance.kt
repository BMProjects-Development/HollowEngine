package ru.hollowhorizon.hollowengine.addons.physics.ragdoll

import com.github.stephengold.joltjni.*
import net.minecraft.core.BlockPos
import ru.hollowhorizon.hollowengine.addons.physics.*
import ru.hollowhorizon.hollowengine.addons.physics.world.BlockColliders
import ru.hollowhorizon.hollowengine.addons.physics.world.PhysicsWorld
import ru.hollowhorizon.hollowengine.common.utils.math.*

/**
 * Where the model stands in the world this frame.
 */
class ModelPlacement(val origin: Vec3f, val rotation: QuatF) {
    fun moved(x: Double, y: Double, z: Double) = ModelPlacement(origin + Vec3f(x.toFloat(), y.toFloat(), z.toFloat()), rotation)
}

/** Where a ragdoll lies: the center of mass of its bodies, and the lowest point they reach. */
class RestingPlace(val x: Double, val y: Double, val z: Double, val floor: Double)

/**
 * One live ragdoll: bodies in the world, and surrounding blocks.
 */
class RagdollInstance private constructor(
    private val template: RagdollTemplate,
    private val spec: RagdollStateSpec,
    private val ragdoll: Ragdoll,
    private val patch: BlockColliders.Patch?,
) : AutoCloseable {
    var lastUsed: Double = 0.0

    /**
     * The pose exchanged with Jolt every frame.
     */
    private val pose = SkeletonPose().apply {
        setSkeleton(template.skeleton)
        repeat(jointCount) { joint ->
            getJoint(joint).setRotation(Quat.sIdentity())
            getJoint(joint).setTranslation(Vec3())
        }
        calculateJointMatrices()
    }
    private val rotation = MutableQuatF()
    private val translation = MutableVec3f()
    private val location = RVec3()
    private val orientation = Quat()

    var isAlive: Boolean = true
        private set

    /**
     * Places the bodies in positions where the animation left the bones and applies the object's own motion to them, so that
     * body that came to a stop in the middle of a step continues to move.
     */
    fun start(placement: ModelPlacement, globals: Map<Int, Mat4f>, velocity: Vec3f) {
        if (!isAlive) return

        writePose(placement, globals)
        ragdoll.setPose(pose)
        if (velocity.sqrLength() > 0f) ragdoll.addLinearVelocity(velocity.toJolt())
    }

    /** Jolt ids for this ragdoll's body, in the order in which its bones are listed in the plan. */
    val bodyIds: IntArray get() = if (isAlive) ragdoll.bodyIds else IntArray(0)

    /**
     * Force with each body pushes whatever it touches, depending on the body's Jolt id; see [ru.hollowhorizon.hollowengine.addons.physics.world.SoftContacts].
     */
    fun softBodies(): Map<Int, Float> {
        if (!isAlive) return emptyMap()

        val ids = ragdoll.bodyIds
        return RagdollCollisions.softBodies(template.plan.bones)
            .mapNotNull { (index, push) -> ids.getOrNull(index)?.let { it to push } }
            .toMap()
    }

    /**
     * Pushes the body in the specified direction.
     */
    fun push(velocity: Vec3f) {
        if (!isAlive) return

        ragdoll.addLinearVelocity(velocity.toJolt())
        val bodies = ragdoll.physicsSystem.bodyInterface
        ragdoll.bodyIds.forEach(bodies::activateBody)
    }

    /**
     * Brings every body up to moving at [velocity], the way something walking into the ragdoll carries it along: a body
     * already moving that way at least as fast gets nothing. Unlike [push], a shove that comes every tick keeps the
     * ragdoll at its pace instead of piling up speed.
     */
    fun nudge(velocity: Vec3f) {
        if (!isAlive) return
        val speed = velocity.length()
        if (speed <= 0f) return
        val direction = velocity * (1f / speed)
        val bodies = ragdoll.physicsSystem.bodyInterface
        val current = Vec3()
        ragdoll.bodyIds.forEach { id ->
            bodies.getLinearVelocity(id, current)
            val along = current.x * direction.x + current.y * direction.y + current.z * direction.z
            val missing = speed - along
            if (missing <= 0f) return@forEach
            bodies.setLinearVelocity(id, current.x + direction.x * missing, current.y + direction.y * missing, current.z + direction.z * missing)
            bodies.activateBody(id)
        }
    }

    /** Retrieves the blocks currently containing the ragdoll; see [PhysicsWorld.stepOnce]. */
    fun beforeStep() {
        if (!isAlive) return
        val patch = patch ?: return
        ragdoll.getRootTransform(location, orientation)
        patch.follow(BlockPos.containing(location.xx(), location.yy(), location.zz()), spec.blockRadius)
    }

    /**
     * Reads the simulated bones back into model space, using the model's own node indices as keys.
     */
    fun readInto(placement: ModelPlacement, store: MutableMap<Int, MutableMat4f>) {
        snapshot()?.readInto(placement, store)
    }

    /** Where every body is in the world now, as the server sends it; null once the ragdoll is gone. */
    fun snapshot(): RagdollSnapshot? {
        if (!isAlive) return null

        ragdoll.getPose(pose)
        val root = pose.rootOffset
        val bones = template.plan.bones
        val values = FloatArray(bones.size * RagdollSnapshot.STRIDE)
        bones.indices.forEach { index ->
            pose.getJointMatrix(index).rotationAndTranslation(rotation, translation)
            val at = index * RagdollSnapshot.STRIDE
            values[at] = translation.x
            values[at + 1] = translation.y
            values[at + 2] = translation.z
            values[at + 3] = rotation.x
            values[at + 4] = rotation.y
            values[at + 5] = rotation.z
            values[at + 6] = rotation.w
        }
        return RagdollSnapshot(root.xx(), root.yy(), root.zz(), IntArray(bones.size) { bones[it].nodeIndex }, values)
    }

    /** Whether any body is still moving; a ragdoll at rest has all of them asleep. */
    val isMoving: Boolean
        get() = isAlive && ragdoll.bodyIds.any(ragdoll.physicsSystem.bodyInterface::isActive)

    /** How heavy each body is, in the order of [bodyIds]. */
    private val masses: FloatArray by lazy {
        val bodies = ragdoll.physicsSystem.bodyInterface
        ragdoll.bodyIds.map { id -> bodies.getShape(id).massProperties.mass }.toFloatArray()
    }

    /**
     * Where the ragdoll lies: the center of mass of its bodies, and the lowest point any of them reaches, which is
     * where whatever stands for the ragdoll in the world puts its feet. Null once the ragdoll is gone.
     */
    fun restingPlace(): RestingPlace? {
        if (!isAlive) return null
        val bodies = ragdoll.physicsSystem.bodyInterface
        val ids = ragdoll.bodyIds
        var mass = 0.0
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var floor = Double.POSITIVE_INFINITY
        ids.forEachIndexed { index, id ->
            val weight = masses.getOrElse(index) { 1f }.toDouble().coerceAtLeast(MIN_MASS)
            val center = bodies.getCenterOfMassPosition(id)
            x += center.xx() * weight
            y += center.yy() * weight
            z += center.zz() * weight
            mass += weight
            floor = minOf(floor, bodies.getTransformedShape(id).worldSpaceBounds.min.y.toDouble())
        }
        if (mass <= 0.0) return null
        return RestingPlace(x / mass, y / mass, z / mass, floor)
    }

    private fun writePose(placement: ModelPlacement, globals: Map<Int, Mat4f>) {
        pose.setRootOffset(
            RVec3(
                placement.origin.x.toDouble(), placement.origin.y.toDouble(), placement.origin.z.toDouble()
            )
        )
        val matrices = pose.jointMatrices

        template.plan.bones.forEachIndexed { index, bone ->
            val global = globals[bone.nodeIndex] ?: return@forEachIndexed
            global.decompose(translation, rotation, null)
            val worldRotation = MutableQuatF(placement.rotation).mul(rotation).norm()
            val worldPosition = Vec3f(translation).rotated(placement.rotation)
            matrices.set(index, matrixOf(worldRotation, worldPosition).toJolt())
        }
    }

    override fun close() {
        if (!isAlive) return

        isAlive = false
        ragdoll.removeFromPhysicsSystem()
        ragdoll.close()
        pose.close()
        patch?.close()
    }

    /**
     * Each part of the ragdoll's body, representing its current position in the world.
     */
    fun forEachBody(action: (RagdollBone, Vec3f, QuatF) -> Unit) {
        if (!isAlive) return

        ragdoll.getPose(pose)
        val rootOffset = pose.rootOffset

        template.plan.bones.forEachIndexed { index, bone ->
            pose.getJointMatrix(index).rotationAndTranslation(rotation, translation)
            action(
                bone,
                Vec3f(
                    (rootOffset.xx() + translation.x).toFloat(),
                    (rootOffset.yy() + translation.y).toFloat(),
                    (rootOffset.zz() + translation.z).toFloat(),
                ),
                QuatF(rotation),
            )
        }
    }

    companion object {
        /** What a body of no measurable mass counts for, so a ragdoll of such bodies still has a center. */
        private const val MIN_MASS = 1.0e-3

        fun create(world: PhysicsWorld, template: RagdollTemplate, spec: RagdollStateSpec): RagdollInstance? {
            val ragdoll = template.instantiate(world.system) ?: return null
            val patch = if (spec.collideWithBlocks) world.blocks.patch() else null
            return RagdollInstance(template, spec, ragdoll, patch)
        }

        /** A ragdoll in a bare system, with no world around it: previews, and tests. */
        fun create(system: PhysicsSystem, template: RagdollTemplate, spec: RagdollStateSpec): RagdollInstance? {
            val ragdoll = template.instantiate(system) ?: return null
            return RagdollInstance(template, spec, ragdoll, patch = null)
        }
    }
}
