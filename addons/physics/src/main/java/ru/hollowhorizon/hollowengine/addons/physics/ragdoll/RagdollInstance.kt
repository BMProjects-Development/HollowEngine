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
class ModelPlacement(val origin: Vec3f, val rotation: QuatF)

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
        if (!isAlive) return

        ragdoll.getPose(pose)
        val rootOffset = pose.rootOffset

        template.plan.bones.forEachIndexed { index, bone ->
            pose.getJointMatrix(index).rotationAndTranslation(rotation, translation)
            val worldPosition = Vec3f(
                (rootOffset.xx() + translation.x - placement.origin.x).toFloat(),
                (rootOffset.yy() + translation.y - placement.origin.y).toFloat(),
                (rootOffset.zz() + translation.z - placement.origin.z).toFloat(),
            )
            val modelPosition = worldPosition.rotatedInverse(placement.rotation)
            val modelRotation = MutableQuatF(placement.rotation).invert().mul(rotation).norm()
            matrixInto(store.getOrPut(bone.nodeIndex) { MutableMat4f() }, modelRotation, modelPosition)
        }
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
