package ru.hollowhorizon.hollowengine.client.ui

import androidx.compose.runtime.staticCompositionLocalOf
import ru.hollowhorizon.hollowengine.client.ui.layout.UiRect

/**
 * Keeps the pointer inside a region for a while, wrapping it around the edges instead of letting it leave, as a drag
 * that should never run out of room needs. A host that can move the system cursor provides it; elsewhere there is
 * none, and the pointer goes where it goes.
 */
interface UiPointerWrap {
    /**
     * Keeps the pointer in [region], in root coordinates, until [release] with the same [onWrap], which is told how far
     * each wrap moved it. [startX] and [startY] are where the owner counts the pointer from.
     */
    fun hold(region: UiRect, startX: Float, startY: Float, onWrap: (dx: Float, dy: Float) -> Unit)

    fun release(onWrap: (dx: Float, dy: Float) -> Unit)
}

val LocalUiPointerWrap = staticCompositionLocalOf<UiPointerWrap?> { null }
