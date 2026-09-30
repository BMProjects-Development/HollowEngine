package ru.hollowhorizon.hollowengine.common.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.hollowhorizon.hollowengine.common.attachments.editor.*
import ru.hollowhorizon.hollowengine.common.utils.math.*

/** Coordinates shared by rig attachments and their inspector fields. */
@Serializable
@SerialName("ru.hollowhorizon.hollowengine.addons.physics.rig.RigVector")
data class RigVector(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    fun toVec3f() = Vec3f(x, y, z)

    companion object {
        val ZERO = RigVector()
        fun of(vector: Vec3f) = RigVector(vector.x, vector.y, vector.z)
    }
}

private const val LANG = "hollowengine.gui.model_editor.hitbox"

/** An oriented damage volume, in the local space of the bone that owns it. */
@Serializable
@SerialName(HitboxAttachmentSpec.TYPE_ID)
data class HitboxAttachmentSpec(
    @EditorName("$LANG.name") override val id: String = "hitbox",
    @EditorName("$LANG.offset") val offset: RigVector = RigVector.ZERO,
    @EditorName("$LANG.rotation")
    @EditorDescription("$LANG.rotation_hint")
    val rotation: RigVector = RigVector.ZERO,
    @EditorName("$LANG.size")
    @EditorDescription("$LANG.size_hint")
    val size: RigVector = RigVector(0.25f, 0.25f, 0.25f),
    @EditorName("$LANG.damage_multiplier") @EditorRange(min = 0.0)
    val damageMultiplier: Float = 1f,
) : RigAttachmentSpec() {
    init {
        require(id.isNotBlank())
        require(listOf(offset.x, offset.y, offset.z, rotation.x, rotation.y, rotation.z).all(Float::isFinite))
        require(listOf(size.x, size.y, size.z).all { it.isFinite() && it > 0f })
        require(damageMultiplier.isFinite() && damageMultiplier >= 0f)
    }

    override fun withId(id: String) = copy(id = id)

    fun matrix(bone: Mat4f): Mat4f = MutableMat4f(bone)
        .translate(offset.toVec3f())
        .rotate(rotation.x.deg, rotation.y.deg, rotation.z.deg)
        .scale(size.toVec3f())

    companion object {
        const val TYPE_ID = "hollowengine:rig/hitbox"
        val TYPE = RigAttachmentType(
            TYPE_ID, HitboxAttachmentSpec::class, serializer(), "$LANG.title",
            createDefault = { HitboxAttachmentSpec(id = it) },
        )
    }
}
