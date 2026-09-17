package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.mutableStateOf
import ru.hollowhorizon.hollowengine.common.config.HollowEngineConfig

/**
 * A font size changed with Ctrl+wheel, as observable state backed by a config property.
 *
 * Mirrors [HollowIdeScale]: the UI reads the state so a change recomposes it, and the write goes
 * through the config property, which schedules its own deferred save.
 */
abstract class HollowIdeZoomableFontSize(private val read: () -> Float, private val write: (Float) -> Unit) {
    private val sizeState = mutableStateOf(read().coerceIn(MinSize, MaxSize))

    var size: Float
        get() = sizeState.value
        set(value) {
            val clamped = value.coerceIn(MinSize, MaxSize)
            if (sizeState.value == clamped) return
            sizeState.value = clamped
            write(clamped)
        }

    /** Applies a wheel notch; [scrollY] follows the usual convention of positive meaning scroll up. */
    fun zoom(scrollY: Float) {
        if (scrollY == 0f) return
        size += if (scrollY > 0f) Step else -Step
    }

    companion object {
        /** Mirrors the `@PropertyRange` of the backing properties; a write outside it throws. */
        const val MinSize = 6f
        const val MaxSize = 36f

        /** One notch of the wheel. Whole points, so the size stays on values a font renders crisply. */
        const val Step = 1f
    }
}

/** Font size of the IDE code editor. */
object HollowIdeFontSize : HollowIdeZoomableFontSize(
    read = { HollowEngineConfig.ideEditorFontSize },
    write = { HollowEngineConfig.ideEditorFontSize = it },
)

/** Font size of the console's log and input. */
object HollowIdeConsoleFontSize : HollowIdeZoomableFontSize(
    read = { HollowEngineConfig.ideConsoleFontSize },
    write = { HollowEngineConfig.ideConsoleFontSize = it },
)
