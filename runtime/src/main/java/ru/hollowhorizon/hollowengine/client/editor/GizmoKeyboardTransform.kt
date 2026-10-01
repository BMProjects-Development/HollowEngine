package ru.hollowhorizon.hollowengine.client.editor

import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.ui.UiCanvasDrawScope
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.client.utils.math.rotateBy
import ru.hollowhorizon.hollowengine.common.utils.math.Vec3f

/** What a key or a click did to a [GizmoKeyboardTransform]. */
enum class GizmoKeyResult {
    /** Not one of its keys; the editor may use it. */
    IGNORED,

    /** The transform changed how it is held; apply [GizmoKeyboardTransform.update] again. */
    CHANGED,

    /** Done: keep what it made. */
    CONFIRMED,

    /** Done: put back [GizmoKeyboardTransform.start]. */
    CANCELLED,
}

/**
 * A transform started from the keyboard, the way Blender does it: T, R or S begins it where the pointer
 * is, the pointer drives it, X, Y or Z holds it to an axis (the world's first, the object's own on a
 * second press, free again on a third), a click or Enter applies it and Escape or the right button puts
 * the object back. Scale is always along the object's own axes.
 */
class GizmoKeyboardTransform(
    val mode: GizmoEditMode,
    val start: GizmoTransformValues,
    private val startX: Float,
    private val startY: Float,
    private val projector: GizmoProjector,
    private val geometry: GizmoGeometry,
    private val manipulator: GizmoManipulator,
) {
    var axis: Int? = null
        private set
    var local: Boolean = false
        private set

    private lateinit var handle: GizmoHandle
    private lateinit var drag: GizmoDrag

    init {
        restart()
    }

    /** Where the pointer at ([x], [y]) takes the object, or null while it cannot tell. */
    fun update(x: Float, y: Float, modifiers: Int): GizmoTransformValues? = manipulator.update(drag, x, y, modifiers)

    fun key(key: Int): GizmoKeyResult = when (key) {
        GLFW.GLFW_KEY_X, GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_Z -> {
            constrain(key - GLFW.GLFW_KEY_X)
            GizmoKeyResult.CHANGED
        }

        GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> GizmoKeyResult.CONFIRMED
        GLFW.GLFW_KEY_ESCAPE -> GizmoKeyResult.CANCELLED
        else -> GizmoKeyResult.IGNORED
    }

    fun click(button: Int): GizmoKeyResult =
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) GizmoKeyResult.CANCELLED else GizmoKeyResult.CONFIRMED

    /** The line of the axis it is held to, and the angle swept while rotating. */
    fun draw(scope: UiCanvasDrawScope) {
        GizmoRenderer.drawRotationSector(scope, geometry, projector, drag)
        GizmoRenderer.drawHandles(scope, listOf(handle), null, null)
    }

    /** What it is doing and which keys do what, for the editor to show by the pointer. */
    val hint: String
        get() {
            val held = axis?.let { "XYZ"[it] + if (local) " " + "hollowengine.gui.ide.gizmo.local".lang else "" }
            return listOfNotNull("hollowengine.gui.ide.gizmo.${mode.name.lowercase()}".lang, held, "hollowengine.gui.ide.gizmo.keys".lang)
                .joinToString("  ·  ")
        }

    /**
     * Holds the transform to [index]: the world's axis, then the object's own, then free again. Starts
     * over from where it began, so motion along the old axis does not carry over.
     */
    private fun constrain(index: Int) {
        when {
            axis != index -> {
                axis = index
                local = mode == GizmoEditMode.SCALE
            }

            !local -> local = true
            else -> {
                axis = null
                local = false
            }
        }
        restart()
    }

    private fun restart() {
        val origin = start.translation.toVec3()
        val direction = axis?.let { index ->
            val unit = UNIT_AXES[index]
            if (local || mode == GizmoEditMode.SCALE) unit.rotateBy(start.rotation) else unit
        }?.toVec3()
        val id = when (mode) {
            GizmoEditMode.TRANSLATE -> axis?.let(TRANSLATE_IDS::get) ?: GizmoHandleId.CENTER
            GizmoEditMode.ROTATE -> ROTATE_IDS[axis ?: 0]
            GizmoEditMode.SCALE -> axis?.let(SCALE_IDS::get) ?: GizmoHandleId.SCALE_UNIFORM
        }
        val turnAround = direction ?: if (mode == GizmoEditMode.ROTATE) towardCamera(origin) else null
        handle = GizmoHandle(
            id = id,
            worldOrigin = origin,
            worldAxis = turnAround,
            renderLines = listOfNotNull(direction?.let { guideLine(origin, it) }),
            fillPolygon = null,
            color = GUIDE_COLORS[axis ?: 0],
            width = GUIDE_WIDTH,
            depth = 0f,
            pick = PickPrimitive.Disc(Pt(0f, 0f), 0f),
        )
        drag = manipulator.begin(handle, start, startX, startY)
    }

    private fun towardCamera(origin: Vec3): Vec3 = projector.cameraPosition.subtract(origin).normalize()

    /** The line through the object along the axis it is held to, across the whole view. */
    private fun guideLine(origin: Vec3, axis: Vec3): GizmoStroke? {
        val reach = axis.scale(GUIDE_REACH_PIXELS * projector.worldPerPixel(origin).toDouble())
        return geometry.projectPolyline(listOf(origin.subtract(reach), origin.add(reach)))?.let(::GizmoStroke)
    }

    private fun Vec3f.toVec3() = Vec3(x.toDouble(), y.toDouble(), z.toDouble())

    companion object {
        /** The transform T, R or S starts, or null for any other key. */
        fun modeFor(key: Int): GizmoEditMode? = when (key) {
            GLFW.GLFW_KEY_T -> GizmoEditMode.TRANSLATE
            GLFW.GLFW_KEY_R -> GizmoEditMode.ROTATE
            GLFW.GLFW_KEY_S -> GizmoEditMode.SCALE
            else -> null
        }

        private const val GUIDE_REACH_PIXELS = 4000f
        private const val GUIDE_WIDTH = 1.5f

        private val UNIT_AXES = listOf(Vec3f.X_AXIS, Vec3f.Y_AXIS, Vec3f.Z_AXIS)
        private val TRANSLATE_IDS = listOf(GizmoHandleId.AXIS_X, GizmoHandleId.AXIS_Y, GizmoHandleId.AXIS_Z)
        private val ROTATE_IDS = listOf(GizmoHandleId.ROTATE_X, GizmoHandleId.ROTATE_Y, GizmoHandleId.ROTATE_Z)
        private val SCALE_IDS = listOf(GizmoHandleId.SCALE_X, GizmoHandleId.SCALE_Y, GizmoHandleId.SCALE_Z)
        private val GUIDE_COLORS = listOf(GizmoColors.AXIS_X, GizmoColors.AXIS_Y, GizmoColors.AXIS_Z)
    }
}
