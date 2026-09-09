package ru.hollowhorizon.hollowengine.client.render

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.render.SkeletonLayout.axis
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/**
 * Skeleton of a model, drawn on the same key as vanilla hitboxes (F3+B).
 */
@ClientOnly
object DebugSkeletonRenderer {
    val isEnabled: Boolean
        get() = Minecraft.getInstance().entityRenderDispatcher.shouldRenderHitBoxes()

    fun render(attachment: ModelAttachment, poseStack: PoseStack, buffers: MultiBufferSource) =
        draw(attachment, DebugLines.batch(buffers, poseStack))

    fun draw(attachment: ModelAttachment, lines: DebugLines.Batch, highlighted: String? = null) {
        SkeletonLayout.of(attachment).forEach { bone ->
            lines.bone(
                bone.head,
                bone.tail,
                bone.roll,
                if (bone.name == highlighted) SELECTED_COLOR else BONE_COLOR,
            )

            bone.links.forEach { link -> lines.line(bone.head, link, LINK_COLOR) }
        }
    }

    private val BONE_COLOR = 0xFFFFFFFF.toInt()
    private val SELECTED_COLOR = 0xFFEB9433.toInt()
    private val LINK_COLOR = 0x66AFC4E0
}
