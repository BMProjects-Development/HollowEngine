package ru.hollowhorizon.hollowengine.addons.physics.collider

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorDescription
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorName
import ru.hollowhorizon.hollowengine.common.colliders.ColliderShapeSpec
import ru.hollowhorizon.hollowengine.common.colliders.ColliderShapeType
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

private const val LANG = "hollowengine.gui.rig_editor.collider.shape"

/** Which axis of the collider's box a round shape stands along. */
@Serializable
enum class ShapeAxis {
    @EditorName("$LANG.axis.x")
    X,

    @EditorName("$LANG.axis.y")
    Y,

    @EditorName("$LANG.axis.z")
    Z,
}

/** The largest ball the box holds. */
@Serializable
@SerialName(SphereColliderShape.TYPE_ID)
data object SphereColliderShape : ColliderShapeSpec() {
    const val TYPE_ID = "hollowengine:physics/collider/sphere"
}

/** A rod with round ends, as long as the box along [axis] and as thick as its thinner side across. */
@Serializable
@SerialName(CapsuleColliderShape.TYPE_ID)
data class CapsuleColliderShape(
    @EditorName("$LANG.axis")
    @EditorDescription("$LANG.axis.hint")
    val axis: ShapeAxis = ShapeAxis.Y,
) : ColliderShapeSpec() {
    companion object {
        const val TYPE_ID = "hollowengine:physics/collider/capsule"
    }
}

/** A drum, as long as the box along [axis] and as thick as its thinner side across. */
@Serializable
@SerialName(CylinderColliderShape.TYPE_ID)
data class CylinderColliderShape(
    @EditorName("$LANG.axis")
    @EditorDescription("$LANG.axis.hint")
    val axis: ShapeAxis = ShapeAxis.Y,
) : ColliderShapeSpec() {
    companion object {
        const val TYPE_ID = "hollowengine:physics/collider/cylinder"
    }
}

/**
 * The smallest convex shape around [points], given in the box's own measure.
 */
@Serializable
@SerialName(HullColliderShape.TYPE_ID)
data class HullColliderShape(
    @EditorName("$LANG.points")
    @EditorDescription("$LANG.points.hint")
    val points: List<Vec3f> = OCTAHEDRON,
) : ColliderShapeSpec() {
    companion object {
        const val TYPE_ID = "hollowengine:physics/collider/hull"

        /** What a new hull starts as: a point in the middle of each face of the box. */
        val OCTAHEDRON = listOf(
            Vec3f(1f, 0f, 0f), Vec3f(-1f, 0f, 0f),
            Vec3f(0f, 1f, 0f), Vec3f(0f, -1f, 0f),
            Vec3f(0f, 0f, 1f), Vec3f(0f, 0f, -1f),
        )
    }
}

internal object PhysicsColliderShapes {
    val TYPES: List<ColliderShapeType<*>> = listOf(
        ColliderShapeType(SphereColliderShape.TYPE_ID, SphereColliderShape::class, SphereColliderShape.serializer(), "$LANG.sphere"),
        ColliderShapeType(CapsuleColliderShape.TYPE_ID, CapsuleColliderShape::class, CapsuleColliderShape.serializer(), "$LANG.capsule"),
        ColliderShapeType(CylinderColliderShape.TYPE_ID, CylinderColliderShape::class, CylinderColliderShape.serializer(), "$LANG.cylinder"),
        ColliderShapeType(HullColliderShape.TYPE_ID, HullColliderShape::class, HullColliderShape.serializer(), "$LANG.hull"),
    )
}
