package ru.hollowhorizon.hollowengine.client.models.internal.rig

import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.render.DebugLines
import ru.hollowhorizon.hollowengine.common.models.hitboxes

object HitboxRigOverlay : RigOverlay {
    override fun draw(model: ModelAttachment, lines: DebugLines.Batch, selected: String?) {
        model.rig.hitboxes(model.nodes).forEach { hitbox ->
            val box = hitbox.geometry
            lines.box(box.center, box.x, box.y, box.z, if (hitbox.bone == selected) SELECTED else NORMAL)
        }
    }

    private val NORMAL = 0xCC4DFF99.toInt()
    private val SELECTED = 0xFFFFA333.toInt()
}
