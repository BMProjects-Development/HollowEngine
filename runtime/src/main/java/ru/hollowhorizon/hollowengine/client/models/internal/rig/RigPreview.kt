package ru.hollowhorizon.hollowengine.client.models.internal.rig

import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationLayer
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimationPose
import ru.hollowhorizon.hollowengine.client.models.internal.animator.AnimatorEvaluationContext
import ru.hollowhorizon.hollowengine.client.models.internal.animator.LayerPose
import ru.hollowhorizon.hollowengine.client.models.internal.animator.PoseTarget
import ru.hollowhorizon.hollowengine.client.models.internal.v2.ModelAttachment
import ru.hollowhorizon.hollowengine.client.render.DebugLines
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.rl

/**
 * A simulation of a rig, running outside the world.
 *
 * Rig editor needs to show what the bodies it is authoring actually do, and only the addon that
 * owns physics can tell it, so the engine asks for one of these and does not care what is behind it.
 */
interface RigPreview : AutoCloseable {
    /** Where the bones are this frame, or null while there is nothing to simulate. */
    fun update(target: PoseTarget, deltaTime: Float): AnimationPose?

    /** A shove, in model space: the editor dragging the model about. */
    fun push(direction: Vec3f)

    /** Draws the bodies as they are right now, in model space. */
    fun draw(lines: DebugLines.Batch)
}

fun interface RigPreviewFactory {
    fun create(): RigPreview?
}

/**
 * Draws what an addon has hung on a model's bones. [selected] is the bone the editor is looking at.
 */
fun interface RigOverlay {
    fun draw(model: ModelAttachment, lines: DebugLines.Batch, selected: String?)
}

object RigOverlays {
    val point = ExtensionPoints.create<RigOverlay>("hollowengine:rig/overlays".rl)

    fun register(id: String, overlay: RigOverlay): ExtensionHandle = point.register(id.rl, overlay)

    val all: List<RigOverlay> get() = point.extensions
}

object RigPreviews {
    val point = ExtensionPoints.create<RigPreviewFactory>("hollowengine:rig/previews".rl)

    fun register(id: String, factory: RigPreviewFactory): ExtensionHandle = point.register(id.rl, factory)

    val isAvailable: Boolean get() = point.extensions.isNotEmpty()

    fun create(): RigPreview? = point.extensions.firstNotNullOfOrNull { it.create() }
}

class RigPreviewLayer(private val preview: RigPreview) : AnimationLayer {
    override val id: String = ID

    override val priority: Int = 1000

    override fun sample(target: PoseTarget, context: AnimatorEvaluationContext): LayerPose? =
        preview.update(target, context.deltaTime)?.let(::LayerPose)

    companion object {
        const val ID = "preview:rig_physics"
    }
}
