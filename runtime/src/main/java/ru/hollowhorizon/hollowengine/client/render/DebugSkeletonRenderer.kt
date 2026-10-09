package ru.hollowhorizon.hollowengine.client.render

import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.common.events.ClientOnly

/**
 * Skeleton of a model as solid bones, drawn on the same key as vanilla hitboxes (F3+B) and in the rig
 * editor's preview.
 */
@ClientOnly
object DebugSkeletonRenderer {
    val isEnabled: Boolean
        get() = Minecraft.getInstance().entityRenderDispatcher.shouldRenderHitBoxes()

    fun render(attachment: ModelAttachment, poseStack: PoseStack, buffers: MultiBufferSource) =
        fill(attachment, DebugShapes.batch(buffers, poseStack, DebugShapes.OVERLAY))

    /**
     * The bones, each in the RGB color [colorOf] gives it, or a neutral one, so bones that carry something
     * stand out; [highlighted] is drawn as selected.
     */
    fun fill(attachment: ModelAttachment, shapes: DebugShapes.Batch, highlighted: String? = null, colorOf: (String) -> Int? = { null }) {
        SkeletonLayout.of(attachment).forEach { bone ->
            val color = if (bone.name == highlighted) SELECTED_FILL
            else colorOf(bone.name)?.let { FILL_ALPHA or (it and RGB_MASK) } ?: BONE_FILL
            shapes.bone(bone.head, bone.tail, bone.roll, color)
        }
    }

    private val BONE_FILL = 0x998A9BB4.toInt()
    private val SELECTED_FILL = 0xCCEB9433.toInt()
    private val FILL_ALPHA = 0xB3000000.toInt()
    private const val RGB_MASK = 0xFFFFFF
}
