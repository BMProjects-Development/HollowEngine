package ru.hollowhorizon.hollowengine.client.ui.widgets

import ru.hollowhorizon.hollowengine.client.ui.*
import ru.hollowhorizon.hollowengine.client.ui.style.UiPaint
import ru.hollowhorizon.hollowengine.client.ui.text.Shadow

enum class UiCheckboxVariant {
    CHECKBOX,
    RADIO,
    SWITCH;

    companion object {
        fun from(value: String?): UiCheckboxVariant = when (value?.trim()?.lowercase()) {
            "radio", "radio-button" -> RADIO
            "switch", "toggle" -> SWITCH
            else -> CHECKBOX
        }
    }
}

enum class UiTextFieldMode {
    SINGLE_LINE,
    MULTI_LINE;

    companion object {
        fun from(value: String?): UiTextFieldMode = when (value?.trim()?.lowercase()) {
            "multi", "multiline", "multi-line", "textarea", "area" -> MULTI_LINE
            else -> SINGLE_LINE
        }
    }
}

enum class UiTextInputFilter {
    ANY,
    INTEGER,
    DECIMAL;

    fun accepts(value: String): Boolean = when (this) {
        ANY -> true
        INTEGER -> value.isEmpty() || value == "-" || value.toIntOrNull() != null
        DECIMAL -> value.isEmpty() || value == "-" || value == "." || value == "-." || value.toFloatOrNull() != null
    }

    companion object {
        fun from(value: String?): UiTextInputFilter = when (value?.trim()?.lowercase()) {
            "int", "integer", "whole" -> INTEGER
            "float", "double", "decimal", "number" -> DECIMAL
            else -> ANY
        }
    }
}

data class UiSliderStyle(
    val trackThickness: UiLength? = null,
    val trackPaint: UiPaint? = null,
    val activeTrackPaint: UiPaint? = null,
    val thumbPaint: UiPaint? = null,
    val thumbBorder: UiBorder? = null,
    val thumbSize: UiSize? = null,
    val radius: Float? = null,
) {
    fun merge(other: UiSliderStyle): UiSliderStyle = UiSliderStyle(
        trackThickness = other.trackThickness ?: trackThickness,
        trackPaint = other.trackPaint ?: trackPaint,
        activeTrackPaint = other.activeTrackPaint ?: activeTrackPaint,
        thumbPaint = other.thumbPaint ?: thumbPaint,
        thumbBorder = other.thumbBorder ?: thumbBorder,
        thumbSize = other.thumbSize ?: thumbSize,
        radius = other.radius ?: radius,
    )
}

data class UiCheckboxStyle(
    val markPaint: UiPaint? = null,
    val activePaint: UiPaint? = null,
    val variant: UiCheckboxVariant? = null,
) {
    fun merge(other: UiCheckboxStyle): UiCheckboxStyle = UiCheckboxStyle(
        markPaint = other.markPaint ?: markPaint,
        activePaint = other.activePaint ?: activePaint,
        variant = other.variant ?: variant,
    )
}

data class UiTextFieldStyle(
    val caretColor: UiColor? = null,
    val selectionColor: UiColor? = null,
    val lineNumberColor: UiColor? = null,
    val lineNumbers: Boolean? = null,
    val inlayHints: Boolean? = null,
    val textShadow: Shadow? = null,
    val textShadowSet: Boolean = false,
) {
    fun merge(other: UiTextFieldStyle): UiTextFieldStyle = UiTextFieldStyle(
        caretColor = other.caretColor ?: caretColor,
        selectionColor = other.selectionColor ?: selectionColor,
        lineNumberColor = other.lineNumberColor ?: lineNumberColor,
        lineNumbers = other.lineNumbers ?: lineNumbers,
        inlayHints = other.inlayHints ?: inlayHints,
        textShadow = if (other.textShadowSet) other.textShadow else textShadow,
        textShadowSet = other.textShadowSet || textShadowSet,
    )
}

internal fun Map<String, String>.readBoolean(name: String, fallback: Boolean = false): Boolean =
    when (this[name]?.lowercase()) {
        "true", "yes", "1", "enabled", "checked" -> true
        "false", "no", "0", "disabled", "unchecked" -> false
        else -> fallback
    }
