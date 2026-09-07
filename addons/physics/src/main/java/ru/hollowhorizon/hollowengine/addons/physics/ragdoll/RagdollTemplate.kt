package ru.hollowhorizon.hollowengine.addons.physics.ragdoll

import com.github.stephengold.joltjni.*
import com.github.stephengold.joltjni.enumerate.EActivation
import com.github.stephengold.joltjni.enumerate.EConstraintSpace
import com.github.stephengold.joltjni.enumerate.EMotionType
import com.github.stephengold.joltjni.enumerate.ESwingType
import ru.hollowhorizon.hollowengine.addons.physics.*
import ru.hollowhorizon.hollowengine.addons.physics.world.PhysicsWorld
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * Description of a single ragdoll model in Jolt: a skeleton, a body for each bone, and a connection between each body and
 * its parent element; all dimensions are defined relative to the model's anchor pose.
 */
class RagdollTemplate private constructor(
    val plan: RagdollPlan,
    val skeleton: Skeleton,
    private val settings: RagdollSettings,
) {
    /** Adds a ragdoll built from this template to [system], or null when Jolt has no bodies left. */
    fun instantiate(system: PhysicsSystem): Ragdoll? =
        settings.createRagdoll(0, 0L, system)?.also { it.addToPhysicsSystem(EActivation.Activate) }

    companion object {
        fun build(plan: RagdollPlan, spec: RagdollStateSpec): RagdollTemplate {
            val skeleton = Skeleton()
            plan.bones.forEach { bone ->
                if (bone.parent < 0) skeleton.addJoint(bone.name) else skeleton.addJoint(bone.name, bone.parent)
            }

            val settings = RagdollSettings()
            settings.setSkeleton(skeleton)
            settings.resizeParts(plan.bones.size)

            val parts = settings.parts
            plan.bones.forEachIndexed { index, bone ->
                val part = parts[index]
                part.setShapeSettings(shapeOf(bone))
                part.setPosition(bone.bindPosition.toJoltPosition())
                part.setRotation(bone.bindRotation.toJolt())
                part.setMotionType(EMotionType.Dynamic)
                part.setObjectLayer(PhysicsWorld.LAYER_DYNAMIC)
                part.setLinearDamping(spec.linearDamping)
                part.setAngularDamping(spec.angularDamping)
                part.setGravityFactor(spec.gravityFactor)
                part.setFriction(spec.friction)
                part.setRestitution(spec.restitution)
                part.setAllowSleeping(true)

                if (bone.parent >= 0) part.setToParent(jointTo(plan.bones[bone.parent], bone))
            }

            settings.stabilize()
            settings.disableParentChildCollisions()
            settings.calculateBodyIndexToConstraintIndex()
            settings.calculateConstraintIndexToBodyIdxPair()

            return RagdollTemplate(plan, skeleton, settings)
        }

        private fun shapeOf(bone: RagdollBone): RotatedTranslatedShapeSettings {
            val halfHeight = (bone.length * 0.5f - bone.radius).coerceAtLeast(MIN_HALF_HEIGHT)
            val capsule = CapsuleShapeSettings(halfHeight, bone.radius)
            capsule.setDensity(bone.density)

            return RotatedTranslatedShapeSettings(
                bone.centreOfMass.toJolt(),
                rotationFromYTo(bone.axis).toJolt(),
                capsule,
            )
        }

        private fun jointTo(parent: RagdollBone, bone: RagdollBone): SwingTwistConstraintSettings {
            val settings = SwingTwistConstraintSettings()
            settings.setSpace(EConstraintSpace.LocalToBodyCom)
            settings.setSwingType(ESwingType.Pyramid)

            val offset = bone.bindPosition.subtract(parent.bindPosition, MutableVec3f())
            val inParent = offset.rotatedInverse(parent.bindRotation).subtract(parent.centreOfMass, MutableVec3f())
            settings.setPosition1(inParent.toJoltPosition())
            settings.setPosition2((bone.centreOfMass * -1f).toJoltPosition())

            val twist = bone.axis
            val plane = perpendicularTo(twist)
            settings.setTwistAxis2(twist.toJolt())
            settings.setPlaneAxis2(plane.toJolt())
            settings.setTwistAxis1(twist.asSeenBy(bone, parent).toJolt())
            settings.setPlaneAxis1(plane.asSeenBy(bone, parent).toJolt())

            settings.setTwistMinAngle(-bone.twistAngle.toRadians())
            settings.setTwistMaxAngle(bone.twistAngle.toRadians())
            settings.setPlaneHalfConeAngle(bone.swingAngle.toRadians())
            settings.setNormalHalfConeAngle(bone.swingAngle.toRadians())
            return settings
        }

        private fun Vec3f.asSeenBy(bone: RagdollBone, parent: RagdollBone): Vec3f =
            Vec3f(rotated(bone.bindRotation).rotatedInverse(parent.bindRotation).normed())

        private fun Float.toRadians(): Float = (this * Math.PI / 180.0).toFloat()

        private const val MIN_HALF_HEIGHT = 0.01f
    }
}
