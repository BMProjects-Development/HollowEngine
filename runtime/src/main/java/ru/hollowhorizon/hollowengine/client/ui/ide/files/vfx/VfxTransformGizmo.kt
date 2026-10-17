package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.editor.*
import ru.hollowhorizon.hollowengine.client.ui.ide.files.HollowIdeVfxDocument
import ru.hollowhorizon.hollowengine.client.utils.math.conjugate
import ru.hollowhorizon.hollowengine.client.utils.math.rotateBy
import ru.hollowhorizon.hollowengine.client.vfx.VfxFrame
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.eulerDegreesXyz
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty
import ru.hollowhorizon.hollowengine.common.vfx.VfxTransform

/**
 * The world's gizmo over the effect preview: the same session, projected from the preview camera into the pixels of
 * the panel.
 */
internal class VfxTransformGizmo {
    private val projector = GizmoProjector()
    val session = GizmoSession(projector)

    /** Takes the preview camera; the handles built after this are in panel pixels. */
    fun capture(preview: VfxPreviewState) {
        val width = preview.viewportWidth
        val height = preview.viewportHeight
        val eye = preview.cameraBasis().eye
        projector.capture(
            view = preview.viewMatrix().setTranslation(0f, 0f, 0f),
            projection = preview.perspective(width, height),
            cameraPosition = Vec3(eye.x.toDouble(), eye.y.toDouble(), eye.z.toDouble()),
            fovDegrees = VfxPreviewFieldOfView,
            width = width,
            height = height,
        )
    }
}

/**
 * A node of the effect as the gizmo moves it, placed where [frame] has it now inside [parent]. Each move writes only the
 * part of the placement it changed, in the parent's space; on a part the timeline drives it becomes a key while the
 * timeline records, and is held until the playhead moves otherwise.
 */
internal class VfxNodeTarget(
    private val document: HollowIdeVfxDocument,
    private val state: VfxEditorState,
    private val nodeId: String,
    frame: VfxFrame,
    parent: VfxFrame?,
    private val start: VfxTransform,
) : GizmoTarget {
    private val parent = parent?.let { VfxFrame().set(it) } ?: VfxFrame()

    override val values = GizmoTransformValues(frame.position.copy(), frame.rotation.copy(), start.scale)

    override fun place(values: GizmoTransformValues, mode: GizmoEditMode) = write(
        when (mode) {
            GizmoEditMode.TRANSLATE -> start.copy(position = parent.toLocalPoint(values.translation))
            GizmoEditMode.ROTATE -> start.copy(rotation = (parent.rotation.conjugate() * values.rotation).eulerDegreesXyz())
            GizmoEditMode.SCALE -> start.copy(scale = values.scale)
        },
    )

    /** Back where it was; what a keyed part held during the transform goes, so the track shows it again. */
    override fun restore() {
        write(start)
        state.session.release(nodeId, Placement)
    }

    override val canReset: Boolean get() = true

    override fun reset(mode: GizmoEditMode): Boolean {
        write(
            when (mode) {
                GizmoEditMode.TRANSLATE -> start.copy(position = Vec3f.ZERO)
                GizmoEditMode.ROTATE -> start.copy(rotation = Vec3f.ZERO)
                GizmoEditMode.SCALE -> start.copy(scale = Vec3f.ONES)
            },
        )
        return true
    }

    override fun beginEdit() = state.beginGesture()

    override fun endEdit() = state.endGesture()

    private fun write(placed: VfxTransform) {
        val node = document.effect.node(nodeId) ?: return
        document.edit { it.withNode(node.withCommon(transform = placed)) }
    }

    private fun VfxFrame.toLocalPoint(world: Vec3f): Vec3f {
        val relative = Vec3f(world.x - position.x, world.y - position.y, world.z - position.z)
            .rotateBy(rotation.conjugate())
        return Vec3f(relative.x / safe(scale.x), relative.y / safe(scale.y), relative.z / safe(scale.z))
    }

    private fun safe(value: Float): Float = if (value in -1.0e-6f..1.0e-6f) 1f else value

    private fun Vec3f.copy(): Vec3f = Vec3f(x, y, z)

    private fun QuatF.copy(): QuatF = QuatF(x, y, z, w)
}

private val Placement = listOf(VfxProperty.POSITION, VfxProperty.ROTATION, VfxProperty.SCALE)

internal val GizmoEditMode.property: VfxProperty
    get() = when (this) {
        GizmoEditMode.TRANSLATE -> VfxProperty.POSITION
        GizmoEditMode.ROTATE -> VfxProperty.ROTATION
        GizmoEditMode.SCALE -> VfxProperty.SCALE
    }
