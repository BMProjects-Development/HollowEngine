package ru.hollowhorizon.hollowengine.client.ui.ide.files.rig

import ru.hollowhorizon.hollowengine.client.models.internal.v2.RuntimeNode
import ru.hollowhorizon.hollowengine.client.render.DebugLines
import ru.hollowhorizon.hollowengine.common.models.ModelRig
import ru.hollowhorizon.hollowengine.common.sounds.SoundBoneAttachmentSpec
import ru.hollowhorizon.hollowengine.common.sounds.outline
import ru.hollowhorizon.hollowengine.common.utils.math.MutableVec3f

/**
 * Outlines the areas the sounds of [rig] fill, where their attachments are in the preview; the one [selected] stands
 * out.
 */
internal fun DebugLines.Batch.soundAreas(rig: ModelRig, roots: List<RuntimeNode>, selected: RigPartSelection?) {
    rig.allAttachments().forEach { (bone, spec) ->
        if (spec !is SoundBoneAttachmentSpec || spec.areas.isEmpty()) return@forEach
        val holder = holderMatrix(roots, bone) ?: return@forEach
        val isSelected = selected != null && selected.bone == bone && selected.id == spec.id
        val color = if (isSelected) SELECTED_COLOR else AREA_COLOR
        spec.areas.forEach { area ->
            area.outline { start, end ->
                line(holder.transform(start, 1f, MutableVec3f()), holder.transform(end, 1f, MutableVec3f()), color)
            }
        }
    }
}

private val AREA_COLOR = 0xFF70BCD8.toInt()
private val SELECTED_COLOR = 0xFFFFA333.toInt()
