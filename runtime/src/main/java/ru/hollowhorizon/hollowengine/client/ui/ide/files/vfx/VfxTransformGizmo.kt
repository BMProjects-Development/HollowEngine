package ru.hollowhorizon.hollowengine.client.ui.ide.files.vfx

import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.client.editor.GizmoDrag
import ru.hollowhorizon.hollowengine.client.editor.GizmoEditMode
import ru.hollowhorizon.hollowengine.client.editor.GizmoGeometry
import ru.hollowhorizon.hollowengine.client.editor.GizmoHandle
import ru.hollowhorizon.hollowengine.client.editor.GizmoHandleId
import ru.hollowhorizon.hollowengine.client.editor.GizmoKeyboardTransform
import ru.hollowhorizon.hollowengine.client.editor.GizmoManipulator
import ru.hollowhorizon.hollowengine.client.editor.GizmoRenderer
import ru.hollowhorizon.hollowengine.client.editor.GizmoPicker
import ru.hollowhorizon.hollowengine.client.editor.GizmoProjector
import ru.hollowhorizon.hollowengine.client.editor.GizmoTransformValues
import ru.hollowhorizon.hollowengine.client.editor.isRotation
import ru.hollowhorizon.hollowengine.client.ui.UiCanvasDrawScope
import ru.hollowhorizon.hollowengine.client.utils.math.conjugate
import ru.hollowhorizon.hollowengine.client.utils.math.rotateBy
import ru.hollowhorizon.hollowengine.client.vfx.VfxFrame
import ru.hollowhorizon.hollowengine.common.utils.math.QuatF
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f
import ru.hollowhorizon.hollowengine.common.utils.math.eulerDegreesXyz
import ru.hollowhorizon.hollowengine.common.vfx.VfxProperty
import ru.hollowhorizon.hollowengine.common.vfx.VfxTransform

/**
 * The transform gizmo of the world, over the effect preview: the same handles, picking and drag math,
 * projected from the preview camera into the pixels of the panel.
 */
internal class VfxTransformGizmo {
    private val projector = GizmoProjector()
    private val geometry = GizmoGeometry(projector)
    private val manipulator = GizmoManipulator(projector)

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

    /**
     * The handles for [frame], the node as it is placed right now. A part of the placement that the
     * timeline drives offers no handles, since the timeline would put it back on the next frame, unless
     * the timeline records: then a drag becomes a key.
     */
    fun handles(frame: VfxFrame, modes: Set<GizmoEditMode>, driven: (VfxProperty) -> VfxDrivenValue?): List<GizmoHandle> {
        val editable = modes.filterTo(HashSet()) { mode -> driven(mode.property)?.recording != false }
        if (editable.isEmpty()) return emptyList()
        return geometry.buildHandles(frame.position.copy(), frame.rotation.copy(), editable)
    }

    fun pick(handles: List<GizmoHandle>, x: Float, y: Float): GizmoHandle? = GizmoPicker.pick(handles, x, y)

    fun begin(handle: GizmoHandle, frame: VfxFrame, parent: VfxFrame?, start: VfxTransform, x: Float, y: Float): VfxTransformDrag {
        val values = GizmoTransformValues(frame.position.copy(), frame.rotation.copy(), start.scale)
        val gizmo = manipulator.begin(handle, values, x, y)
        return VfxTransformDrag(gizmo, start, parent?.let { VfxFrame().set(it) } ?: VfxFrame())
    }

    /** The placement [drag] has come to with the pointer at ([x], [y]), or null while nothing moved. */
    fun drag(drag: VfxTransformDrag, x: Float, y: Float, modifiers: Int): VfxTransform? {
        val values = manipulator.update(drag.gizmo, x, y, modifiers) ?: return null
        return place(drag.gizmo.handleId.mode, values, drag.start, drag.parent)
    }

    /** A transform from the keyboard on the node at [frame], starting where the pointer is. */
    fun keyboard(
        nodeId: String, mode: GizmoEditMode, frame: VfxFrame, parent: VfxFrame?, start: VfxTransform, x: Float, y: Float,
    ): VfxKeyboardTransform {
        val values = GizmoTransformValues(frame.position.copy(), frame.rotation.copy(), start.scale)
        val keyboard = GizmoKeyboardTransform(mode, values, x, y, projector, geometry, manipulator)
        return VfxKeyboardTransform(nodeId, start, parent?.let { VfxFrame().set(it) } ?: VfxFrame(), keyboard)
    }

    /** The placement [transform] has come to with the pointer at ([x], [y]), or null while it cannot tell. */
    fun update(transform: VfxKeyboardTransform, x: Float, y: Float, modifiers: Int): VfxTransform? {
        val values = transform.keyboard.update(x, y, modifiers) ?: return null
        return place(transform.keyboard.mode, values, transform.start, transform.parent)
    }

    /** What [values] make of the node's placement in [parent]'s space: only the part [mode] moves. */
    private fun place(mode: GizmoEditMode, values: GizmoTransformValues, start: VfxTransform, parent: VfxFrame): VfxTransform =
        when (mode) {
            GizmoEditMode.TRANSLATE -> start.copy(position = parent.toLocalPoint(values.translation))
            GizmoEditMode.ROTATE -> start.copy(rotation = (parent.rotation.conjugate() * values.rotation).eulerDegreesXyz())
            GizmoEditMode.SCALE -> start.copy(scale = values.scale)
        }

    fun draw(scope: UiCanvasDrawScope, handles: List<GizmoHandle>, hovered: GizmoHandleId?, drag: VfxTransformDrag?) {
        drag?.gizmo?.let { GizmoRenderer.drawRotationSector(scope, geometry, projector, it) }
        GizmoRenderer.drawHandles(scope, handles, hovered, drag?.gizmo?.handleId)
    }

    /** Where the value label of [drag] goes: next to the origin of the node, in panel pixels. */
    fun labelAt(drag: VfxTransformDrag): Pair<Float, Float>? {
        val screen = projector.project(drag.gizmo.origin) ?: return null
        return if (screen.onScreen) screen.x to screen.y else null
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

/** A transform from the keyboard on a node, with its placement and parent when it began. */
internal class VfxKeyboardTransform(
    val nodeId: String,
    val start: VfxTransform,
    val parent: VfxFrame,
    val keyboard: GizmoKeyboardTransform,
)

/** A gizmo drag on a node: the drag of the gizmo, and the node's placement and parent when it began. */
internal class VfxTransformDrag(val gizmo: GizmoDrag, val start: VfxTransform, val parent: VfxFrame) {
    /** What the value label shows: a distance, an angle in degrees or a scale factor. */
    val label: Float get() = gizmo.labelValue.toFloat()
}

private val GizmoHandleId.mode: GizmoEditMode
    get() = when (this) {
        GizmoHandleId.SCALE_X, GizmoHandleId.SCALE_Y, GizmoHandleId.SCALE_Z, GizmoHandleId.SCALE_UNIFORM ->
            GizmoEditMode.SCALE

        else -> if (isRotation()) GizmoEditMode.ROTATE else GizmoEditMode.TRANSLATE
    }
