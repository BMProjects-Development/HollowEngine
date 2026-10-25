package ru.hollowhorizon.hollowengine.client.ui.widgets

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A drop indicator never changes the real selection, focus or undo history. */
internal class TextFieldDropPreview {
    var offset: Int? by mutableStateOf(null)
        private set
    private var point: Pair<Float, Float>? = null

    fun show(offset: Int) {
        this.offset = offset
    }

    fun track(x: Float, y: Float, offsetAtPoint: ((Float, Float) -> Int)?) {
        point = x to y
        refresh(offsetAtPoint)
    }

    fun refresh(offsetAtPoint: ((Float, Float) -> Int)?) {
        val (x, y) = point ?: return
        offsetAtPoint?.invoke(x, y)?.let(::show)
    }

    fun clear() {
        point = null
        offset = null
    }
}
