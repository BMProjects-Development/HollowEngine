package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.client.editor.GizmoSession
import ru.hollowhorizon.hollowengine.client.ui.ide.HollowIdePointerWrap.release
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect

/** A part of the IDE that keeps pointer inside itself during a transform. */
object HollowIdePointerWrap {
    private class Hold(
        val region: UiRect,
        val start: HollowIdeOverlayPoint,
        val onWrap: (dx: Float, dy: Float) -> Unit,
    ) {
        var started = false
        var lastShiftX = 0f
        var lastShiftY = 0f
        var lastWrapNanos = 0L
    }

    @Volatile
    private var hold: Hold? = null

    /**
     * Keeps the pointer in [region], in the IDE's coordinates, until [release] with the same [onWrap]. [startX] and
     * [startY] are where the owner counts the pointer from: one still outside is brought there first.
     */
    fun hold(region: UiRect, startX: Float, startY: Float, onWrap: (dx: Float, dy: Float) -> Unit) {
        val start = HollowIdeOverlayPoint(
            startX.coerceIn(region.x, region.x + region.width),
            startY.coerceIn(region.y, region.y + region.height),
        )
        hold = Hold(region, start, onWrap)
    }

    fun release(onWrap: (dx: Float, dy: Float) -> Unit) {
        if (hold?.onWrap == onWrap) hold = null
    }

    /**
     * [point], or where it belongs inside the region held, with the window's cursor put there too. On the main
     * thread, where the pointer arrives.
     */
    internal fun wrap(point: HollowIdeOverlayPoint): HollowIdeOverlayPoint {
        val hold = hold ?: return point
        val region = hold.region
        if (region.width <= 2f || region.height <= 2f) return point
        if (!hold.started) {
            hold.started = true
            if (!region.contains(point.x, point.y)) return moveCursor(hold.start)
        }
        if (region.contains(point.x, point.y)) return point
        val back = HollowIdeOverlayPoint(point.x - hold.lastShiftX, point.y - hold.lastShiftY)
        if (System.nanoTime() - hold.lastWrapNanos < StaleNanos && region.contains(back.x, back.y)) return back
        val wrapped = HollowIdeOverlayPoint(
            region.x + (point.x - region.x).mod(region.width),
            region.y + (point.y - region.y).mod(region.height),
        )
        hold.lastShiftX = point.x - wrapped.x
        hold.lastShiftY = point.y - wrapped.y
        hold.lastWrapNanos = System.nanoTime()
        hold.onWrap(hold.lastShiftX, hold.lastShiftY)
        return moveCursor(wrapped)
    }

    private fun moveCursor(point: HollowIdeOverlayPoint): HollowIdeOverlayPoint {
        val cursor = hollowIdeCursorPoint(point.x, point.y)
        GLFW.glfwSetCursorPos(Minecraft.getInstance().window.window, cursor.x.toDouble(), cursor.y.toDouble())
        return point
    }

    /** How long after a wrap a move from beyond the crossed edge counts as one the system queued before it. */
    private const val StaleNanos = 150_000_000L
}

/**
 * While [session] runs a transform from the keyboard, the pointer stays in [bounds], the view, wrapping around it;
 * [pointerX] and [pointerY] are where in the view the transform counts the pointer from.
 */
@Composable
fun GizmoPointerWrap(session: GizmoSession, bounds: UiRect, pointerX: Float, pointerY: Float) {
    val active = session.keyboard != null
    DisposableEffect(active, bounds) {
        if (active) HollowIdePointerWrap.hold(bounds, bounds.x + pointerX, bounds.y + pointerY, session::shiftPointer)
        onDispose { release(session::shiftPointer) }
    }
}
