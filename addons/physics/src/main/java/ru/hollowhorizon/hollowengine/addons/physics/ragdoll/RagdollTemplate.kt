package ru.hollowhorizon.hollowengine.addons.physics.ragdoll

import com.github.stephengold.joltjni.*
import com.github.stephengold.joltjni.enumerate.EActivation
import com.github.stephengold.joltjni.enumerate.EConstraintSpace
import com.github.stephengold.joltjni.enumerate.EMotionType
import com.github.stephengold.joltjni.enumerate.ESwingType
import ru.hollowhorizon.hollowengine.addons.physics.*
import ru.hollowhorizon.hollowengine.addons.physics.rig.AxisLimit
import ru.hollowhorizon.hollowengine.addons.physics.rig.JointLimits
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

            val collisions = collisionFilter(plan)
            plan.bones.indices.forEach { index ->
                parts[index].setCollisionGroup(CollisionGroup(collisions, 0, index))
            }

            settings.stabilize()
            settings.calculateBodyIndexToConstraintIndex()
            settings.calculateConstraintIndexToBodyIdxPair()

            return RagdollTemplate(plan, skeleton, settings)
        }

        /**
         * Who may collide with whom.
         */
        private fun collisionFilter(plan: RagdollPlan): GroupFilterTableRef {
            val filter = GroupFilterTable(plan.bones.size)
            RagdollCollisions.excludedPairs(plan.bones).forEach { (first, second) ->
                filter.disableCollision(first, second)
            }
            return filter.toRef()
        }

        private fun shapeOf(bone: RagdollBone): RotatedTranslatedShapeSettings {
            val shape = when (val authored = bone.shape) {
                is RagdollShape.Capsule -> {
                    val halfHeight = (authored.length * 0.5f - authored.radius).coerceAtLeast(MIN_HALF_HEIGHT)
                    CapsuleShapeSettings(halfHeight, authored.radius)
                }

                is RagdollShape.Box -> BoxShapeSettings(authored.halfExtents.toJolt())
                is RagdollShape.Sphere -> SphereShapeSettings(authored.radius)
            }
            // A body of no density has no mass, and Jolt divides by it.
            shape.setDensity(bone.density.coerceAtLeast(MIN_DENSITY))

            return RotatedTranslatedShapeSettings(
                bone.shape.center.toJolt(),
                bone.shape.rotation.toJolt(),
                shape,
            )
        }

        /**
         * Constraint holding [bone] to [parent].
         */
        private fun jointTo(parent: RagdollBone, bone: RagdollBone): TwoBodyConstraintSettings {
            val pivot = bone.bindPosition + bone.pivot.rotated(bone.bindRotation)
            val offset = pivot.subtract(parent.bindPosition, MutableVec3f())
            val inParent = offset.rotatedInverse(parent.bindRotation).subtract(parent.centreOfMass, MutableVec3f())
            val onBone = bone.pivot - bone.centreOfMass

            val limits = bone.limits
            val hingeAxis = limits.hingeAxis
            return when {
                limits.isFixed -> fixed(parent, bone, inParent, onBone)
                hingeAxis != null -> hinge(parent, bone, hingeAxis, limits.axes[hingeAxis], inParent, onBone)
                else -> cone(parent, bone, limits, inParent, onBone)
            }
        }

        /**
         * Joint that bends more than one way, a twist along the bone, and a cone across it.
         */
        private fun cone(
            parent: RagdollBone,
            bone: RagdollBone,
            limits: JointLimits,
            inParent: Vec3f,
            onBone: Vec3f,
        ): SwingTwistConstraintSettings {
            val settings = SwingTwistConstraintSettings()
            settings.setSpace(EConstraintSpace.LocalToBodyCom)
            settings.setSwingType(ESwingType.Pyramid)
            settings.setPosition1(inParent.toJoltPosition())
            settings.setPosition2(onBone.toJoltPosition())

            val along = alongBone(bone)
            val twist = axisOf(along)
            val plane = axisOf((along + 1) % AXES)
            settings.setTwistAxis2(twist.toJolt())
            settings.setPlaneAxis2(plane.toJolt())
            settings.setTwistAxis1(twist.asSeenBy(bone, parent).toJolt())
            settings.setPlaneAxis1(plane.asSeenBy(bone, parent).toJolt())

            settings.setTwistMinAngle(limits.axes[along].min.coerceIn(-MAX_ANGLE, 0f).toRadians())
            settings.setTwistMaxAngle(limits.axes[along].max.coerceIn(0f, MAX_ANGLE).toRadians())
            settings.setPlaneHalfConeAngle(limits.axes[(along + 1) % AXES].reach.coerceIn(0f, MAX_ANGLE).toRadians())
            settings.setNormalHalfConeAngle(limits.axes[(along + 2) % AXES].reach.coerceIn(0f, MAX_ANGLE).toRadians())
            return settings
        }

        /** Which of the bone's own axes runs along it, and is therefore the one it twists around. */
        private fun alongBone(bone: RagdollBone): Int {
            val axis = bone.shape.axis
            return (0 until AXES).maxBy { kotlin.math.abs(axisOf(it) dot axis) }
        }

        /**
         * One axis open and the rest locked (a knee, an elbow, a lid).
         */
        private fun hinge(
            parent: RagdollBone,
            bone: RagdollBone,
            axisIndex: Int,
            limit: AxisLimit,
            inParent: Vec3f,
            onBone: Vec3f,
        ): HingeConstraintSettings {
            val settings = HingeConstraintSettings()
            settings.setSpace(EConstraintSpace.LocalToBodyCom)
            settings.setPoint1(inParent.toJoltPosition())
            settings.setPoint2(onBone.toJoltPosition())

            val axis = axisOf(axisIndex)
            val normal = perpendicularTo(axis)
            settings.setHingeAxis2(axis.toJolt())
            settings.setNormalAxis2(normal.toJolt())
            settings.setHingeAxis1(axis.asSeenBy(bone, parent).toJolt())
            settings.setNormalAxis1(normal.asSeenBy(bone, parent).toJolt())

            settings.setLimitsMin(minOf(limit.min, limit.max).coerceIn(-MAX_ANGLE, MAX_ANGLE).toRadians())
            settings.setLimitsMax(maxOf(limit.min, limit.max).coerceIn(-MAX_ANGLE, MAX_ANGLE).toRadians())
            return settings
        }

        /** The bone's own axes, in the order the limits are written in. */
        private fun axisOf(index: Int): Vec3f = when (index) {
            0 -> Vec3f.X_AXIS
            1 -> Vec3f.Y_AXIS
            else -> Vec3f.Z_AXIS
        }

        private fun fixed(
            parent: RagdollBone,
            bone: RagdollBone,
            inParent: Vec3f,
            onBone: Vec3f,
        ): FixedConstraintSettings {
            val settings = FixedConstraintSettings()
            settings.setSpace(EConstraintSpace.LocalToBodyCom)
            settings.setAutoDetectPoint(false)
            settings.setPoint1(inParent.toJoltPosition())
            settings.setPoint2(onBone.toJoltPosition())

            settings.setAxisX2(Vec3f.X_AXIS.toJolt())
            settings.setAxisY2(Vec3f.Y_AXIS.toJolt())
            settings.setAxisX1(Vec3f.X_AXIS.asSeenBy(bone, parent).toJolt())
            settings.setAxisY1(Vec3f.Y_AXIS.asSeenBy(bone, parent).toJolt())
            return settings
        }

        private fun Vec3f.asSeenBy(bone: RagdollBone, parent: RagdollBone): Vec3f =
            Vec3f(rotated(bone.bindRotation).rotatedInverse(parent.bindRotation).normed())

        private fun Float.toRadians(): Float = (this * Math.PI / 180.0).toFloat()

        private const val MIN_HALF_HEIGHT = 0.01f
        private const val AXES = 3
        private const val MIN_DENSITY = 1f
        private const val MAX_ANGLE = 179f
    }
}
