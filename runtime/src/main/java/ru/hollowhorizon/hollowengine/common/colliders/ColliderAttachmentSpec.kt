package ru.hollowhorizon.hollowengine.common.colliders

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorDescription
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorName
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.RigAttachmentType
import ru.hollowhorizon.hollowengine.common.utils.math.Mat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableMat4f
import ru.hollowhorizon.hollowengine.common.utils.math.MutableQuatF
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.deg

private const val LANG = "hollowengine.gui.rig_editor.collider"

/** How a collider turns. */
@Serializable
enum class ColliderAlignment {
    /** Turns with its bone and with the entity: an oriented box. */
    ORIENTED,

    /** Stays square to the world, only its center follows the bone: an axis-aligned box. */
    WORLD,
}

/** What a collider takes part in. */
@Serializable
data class ColliderModes(
    @EditorName("$LANG.modes.hit")
    @EditorDescription("$LANG.modes.hit.hint")
    val hit: Boolean = true,
    @EditorName("$LANG.modes.interact")
    @EditorDescription("$LANG.modes.interact.hint")
    val interact: Boolean = false,
    @EditorName("$LANG.modes.push")
    @EditorDescription("$LANG.modes.push.hint")
    val push: Boolean = false,
) {
    /** Whether the crosshair stops at this collider. */
    val isTarget: Boolean get() = hit || interact
}

/**
 * A box on a bone, or on the model when it hangs on no bone.
 */
@Serializable
@SerialName(ColliderAttachmentSpec.TYPE_ID)
data class ColliderAttachmentSpec(
    @EditorName("$LANG.name")
    @EditorDescription("$LANG.name.hint")
    override val id: String = "collider",
    @EditorName("$LANG.alignment")
    @EditorDescription("$LANG.alignment.hint")
    val alignment: ColliderAlignment = ColliderAlignment.ORIENTED,
    @EditorName("$LANG.offset")
    val offset: Vec3f = Vec3f.ZERO,
    /** Euler degrees, applied X, then Y, then Z. */
    @EditorName("$LANG.rotation")
    @EditorDescription("$LANG.rotation.hint")
    val rotation: Vec3f = Vec3f.ZERO,
    @EditorName("$LANG.size")
    val size: Vec3f = Vec3f(DEFAULT_SIZE, DEFAULT_SIZE, DEFAULT_SIZE),
    @EditorName("$LANG.modes")
    val modes: ColliderModes = ColliderModes(),
) : RigAttachmentSpec() {
    override fun withId(id: String) = copy(id = id)

    val orientation: QuatF get() = eulerRotation(rotation)

    /** The unit cube placed in the space of what the collider hangs on. */
    fun localMatrix(): Mat4f = MutableMat4f().translate(offset).rotate(orientation).scale(size)

    companion object {
        const val TYPE_ID = "hollowengine:rig/collider"
        const val DEFAULT_SIZE = 0.25f

        val TYPE = RigAttachmentType(
            id = TYPE_ID,
            specClass = ColliderAttachmentSpec::class,
            serializer = serializer(),
            titleKey = "hollowengine.gui.rig_editor.kind_collider",
            createDefault = { id -> ColliderAttachmentSpec(id = id) },
            allowedOnModel = true,
        )
    }
}

/** Euler degrees as a rotation, applied X, then Y, then Z. */
internal fun eulerRotation(degrees: Vec3f): QuatF = MutableQuatF()
    .setIdentity()
    .rotate(degrees.x.deg, Vec3f.X_AXIS)
    .rotate(degrees.y.deg, Vec3f.Y_AXIS)
    .rotate(degrees.z.deg, Vec3f.Z_AXIS)
