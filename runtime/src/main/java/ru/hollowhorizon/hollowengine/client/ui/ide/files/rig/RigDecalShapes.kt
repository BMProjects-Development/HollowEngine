package ru.hollowhorizon.hollowengine.client.ui.ide.files.rig

import org.joml.Matrix4f
import ru.hollowhorizon.hollowengine.client.decals.DecalExtents
import ru.hollowhorizon.hollowengine.client.decals.DecalGround
import ru.hollowhorizon.hollowengine.client.decals.DecalGuidePart
import ru.hollowhorizon.hollowengine.client.decals.DecalShapes
import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.render.DebugLines
import ru.hollowhorizon.hollowengine.client.utils.math.asMatrix4f
import ru.hollowhorizon.hollowengine.common.decals.DecalAttachmentSpec
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * Shows how the decals of [rig] are cast, where their attachments are in the preview: what each reaches, and for the
 * one [selected] also the face it is cast from, the top of its picture and the way it is cast.
 */
internal fun DebugLines.Batch.decalShapes(rig: ModelRig, roots: List<RuntimeNode>, selected: RigPartSelection?) {
    rig.allAttachments().forEach { (bone, spec) ->
        if (spec !is DecalAttachmentSpec) return@forEach
        val holder = holderMatrix(roots, bone) ?: return@forEach
        val isSelected = selected != null && selected.bone == bone && selected.id == spec.id
        val placement = Matrix4f(holder.asMatrix4f()).mul(spec.localTransform().matrixF.asMatrix4f())
        val extent = DecalExtents.of(spec.decal, placement, DecalGround.floor())
        val shape = Matrix4f(placement).mul(DecalShapes.unitCube(spec.decal, extent))
        if (!isSelected) {
            DecalShapes.edges(shape) { start, end -> line(Vec3f(start.x, start.y, start.z), Vec3f(end.x, end.y, end.z), DECAL_COLOR) }
            return@forEach
        }
        DecalShapes.guide(shape) { start, end, part ->
            line(Vec3f(start.x, start.y, start.z), Vec3f(end.x, end.y, end.z), colorOf(part))
        }
    }
}

/** Whether a decal drawn in the preview is the attachment [selected], by its `bone/id` key. */
internal fun RigPartSelection?.picksDecal(key: String): Boolean = this != null && "${bone.orEmpty()}/$id" == key

private fun colorOf(part: DecalGuidePart): Int = when (part) {
    DecalGuidePart.VOLUME -> SELECTED_COLOR
    DecalGuidePart.SOURCE -> SOURCE_COLOR
    DecalGuidePart.DIRECTION -> DIRECTION_COLOR
}

private val DECAL_COLOR = 0xFFC77DDB.toInt()
private val SELECTED_COLOR = 0xFFFFA333.toInt()
private val SOURCE_COLOR = 0xFFFFE08A.toInt()
private val DIRECTION_COLOR = 0xFF7FD4FF.toInt()
