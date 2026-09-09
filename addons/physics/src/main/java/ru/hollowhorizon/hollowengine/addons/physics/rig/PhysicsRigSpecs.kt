package ru.hollowhorizon.hollowengine.addons.physics.rig

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorBone
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorDescription
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorHidden
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorName
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentType
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import kotlin.math.abs

private const val LANG = "hollowengine.gui.rig_editor.physics"

@Serializable
data class RigVector(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    fun toVec3f(): Vec3f = Vec3f(x, y, z)

    companion object {
        val ZERO = RigVector()

        fun of(vector: Vec3f) = RigVector(vector.x, vector.y, vector.z)
    }
}

/**
 * Shape of a body, in the space of bone it hangs on.
 */
@Serializable
sealed class RigidBodyShape {
    /** Where the middle of shape sits, relative to bone's origin. */
    abstract val offset: RigVector

    /** How the shape is turned in the bone's space, in degrees. */
    abstract val rotation: RigVector

    @Serializable
    @SerialName("capsule")
    data class Capsule(
        @EditorName("$LANG.capsule.radius")
        @EditorDescription("$LANG.capsule.radius.hint")
        val radius: Float = 0.08f,
        @EditorName("$LANG.capsule.length")
        @EditorDescription("$LANG.capsule.length.hint")
        val length: Float = 0.3f,
        @EditorName("$LANG.shape.offset")
        @EditorDescription("$LANG.shape.offset.hint")
        override val offset: RigVector = RigVector.ZERO,
        @EditorName("$LANG.shape.rotation")
        @EditorDescription("$LANG.capsule.rotation.hint")
        override val rotation: RigVector = RigVector.ZERO,
    ) : RigidBodyShape()

    @Serializable
    @SerialName("box")
    data class Box(
        @EditorName("$LANG.box.half_extents")
        @EditorDescription("$LANG.box.half_extents.hint")
        val halfExtents: RigVector = RigVector(
            0.1f,
            0.1f,
            0.1f
        ),
        @EditorName("$LANG.shape.offset")
        @EditorDescription("$LANG.shape.offset.hint")
        override val offset: RigVector = RigVector.ZERO,
        @EditorName("$LANG.shape.rotation")
        @EditorDescription("$LANG.shape.rotation.hint")
        override val rotation: RigVector = RigVector.ZERO,
    ) : RigidBodyShape()

    @Serializable
    @SerialName("sphere")
    data class Sphere(
        @EditorName("$LANG.sphere.radius")
        @EditorDescription("$LANG.sphere.radius.hint")
        val radius: Float = 0.1f,
        @EditorName("$LANG.shape.offset")
        @EditorDescription("$LANG.shape.offset.hint")
        override val offset: RigVector = RigVector.ZERO,
        @EditorHidden override val rotation: RigVector = RigVector.ZERO,
    ) : RigidBodyShape()
}

/**
 * How a body gets on with the other bodies of the same rig.
 */
@Serializable
data class BodyCollision(
    @EditorName("$LANG.collision.with_rig")
    @EditorDescription("$LANG.collision.with_rig.hint")
    val withRig: Boolean = true,
    @EditorBone @EditorName("$LANG.collision.ignores")
    @EditorDescription("$LANG.collision.ignores.hint")
    val ignores: Set<String> = emptySet(),
    @EditorName("$LANG.collision.pushes_out")
    @EditorDescription("$LANG.collision.pushes_out.hint")
    val pushesOut: Boolean = false,
    @EditorName("$LANG.collision.push")
    @EditorDescription("$LANG.collision.push.hint")
    val push: Float = 1f,
) {
    val isSolid: Boolean get() = push >= 1f
}

/**
 * Physics body on a bone.
 */
@Serializable
@SerialName(RigidBodyAttachmentSpec.TYPE_ID)
data class RigidBodyAttachmentSpec(
    @EditorHidden override val id: String = "body",

    @EditorName("$LANG.body.shape")
    @EditorDescription("$LANG.body.shape.hint")
    val shape: RigidBodyShape = RigidBodyShape.Capsule(),
    @EditorName("$LANG.body.density")
    @EditorDescription("$LANG.body.density.hint")
    val density: Float = 1050f,
    @EditorName("$LANG.body.linear_damping")
    @EditorDescription("$LANG.body.linear_damping.hint")
    val linearDamping: Float = 0.05f,
    @EditorName("$LANG.body.angular_damping")
    @EditorDescription("$LANG.body.angular_damping.hint")
    val angularDamping: Float = 0.15f,
    @EditorName("$LANG.body.gravity")
    @EditorDescription("$LANG.body.gravity.hint")
    val gravityFactor: Float = 1f, @EditorName("$LANG.body.friction")

    @EditorDescription("$LANG.body.friction.hint"
    )
    val friction: Float = 0.6f,
    @EditorName("$LANG.body.restitution")
    @EditorDescription("$LANG.body.restitution.hint")
    val restitution: Float = 0f,
    @EditorName("$LANG.body.collision")
    val collision: BodyCollision = BodyCollision(),
) : RigAttachmentSpec() {
    override fun withId(id: String) = copy(id = id)

    companion object {
        const val TYPE_ID = "hollowengine:physics/rigid_body"

        val TYPE = RigAttachmentType(
            id = TYPE_ID,
            specClass = RigidBodyAttachmentSpec::class,
            serializer = serializer(),
            titleKey = "hollowengine.gui.rig_editor.kind_rigid_body",
            createDefault = { id -> RigidBodyAttachmentSpec(id = id) },
        )
    }
}

/**
 * How far a bone may turn around one of its own axes, in degrees.
 */
@Serializable
data class AxisLimit(
    @EditorName("$LANG.axis.min") @EditorDescription("$LANG.axis.min.hint") val min: Float = 0f,

    @EditorName("$LANG.axis.max") @EditorDescription("$LANG.axis.max.hint") val max: Float = 0f,
) {
    val isLocked: Boolean get() = max - min < MIN_RANGE

    /** How far either way the axis reaches; what a joint that has to be symmetric is given. */
    val reach: Float get() = maxOf(abs(min), abs(max))

    companion object {
        val LOCKED = AxisLimit()

        fun of(reach: Float) = AxisLimit(-reach, reach)

        const val MIN_RANGE = 0.5f
    }
}

/**
 * How far a joint lets a bone turn, one axis at a time.
 */
@Serializable
data class JointLimits(
    @EditorName("$LANG.limits.x")
    @EditorDescription("$LANG.limits.x.hint")
    val x: AxisLimit = AxisLimit(-30f, 30f),
    @EditorName("$LANG.limits.y")
    @EditorDescription("$LANG.limits.y.hint")
    val y: AxisLimit = AxisLimit(-55f, 55f),
    @EditorName("$LANG.limits.z")
    @EditorDescription("$LANG.limits.z.hint")
    val z: AxisLimit = AxisLimit(-55f, 55f),
) {
    val axes: List<AxisLimit> get() = listOf(x, y, z)

    /** The one axis left open, or null when the joint is welded or bends more than one way. */
    val hingeAxis: Int?
        get() = axes.indexOfFirst { !it.isLocked }.takeIf { it >= 0 && axes.count { a -> !a.isLocked } == 1 }

    val isFixed: Boolean get() = axes.all(AxisLimit::isLocked)

    companion object {
        val FIXED = JointLimits(AxisLimit.LOCKED, AxisLimit.LOCKED, AxisLimit.LOCKED)

        fun hinge(min: Float, max: Float) = JointLimits(AxisLimit(min, max), AxisLimit.LOCKED, AxisLimit.LOCKED)
    }
}

/**
 * What holds a bone's body to another one. Physical skeleton, which is not always the model's.
 */
@Serializable
@SerialName(JointAttachmentSpec.TYPE_ID)
data class JointAttachmentSpec(
    @EditorHidden
    override val id: String = "joint",
    @EditorBone
    @EditorName("$LANG.joint.parent")
    @EditorDescription("$LANG.joint.parent.hint")
    val parent: String = "",
    @EditorName("$LANG.joint.limits")
    val limits: JointLimits = JointLimits(),
    @EditorName("$LANG.joint.pivot")
    @EditorDescription("$LANG.joint.pivot.hint")
    val pivot: RigVector = RigVector.ZERO,
) : RigAttachmentSpec() {
    override fun withId(id: String) = copy(id = id)

    companion object {
        const val TYPE_ID = "hollowengine:physics/joint"

        val TYPE = RigAttachmentType(
            id = TYPE_ID,
            specClass = JointAttachmentSpec::class,
            serializer = serializer(),
            titleKey = "hollowengine.gui.rig_editor.kind_joint",
            createDefault = { id -> JointAttachmentSpec(id = id) },
        )
    }
}
