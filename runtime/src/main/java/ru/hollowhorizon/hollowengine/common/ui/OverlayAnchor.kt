package ru.hollowhorizon.hollowengine.common.ui

import net.minecraft.resources.ResourceLocation

/**
 * A place an overlay draws in. An overlay lists them in priority: while a screen is open, the first [OnScreen]
 * that accepts it wins; otherwise the overlay draws at its [HudLayer], if it has one.
 */
sealed interface OverlayAnchor {
    /**
     * Next to a vanilla HUD layer. The game draws the HUD under an open screen, and the overlay with it; there it
     * takes no input, and with no screen its pointer is the center of the screen.
     */
    data class HudLayer(val layer: ResourceLocation, val placement: HudPlacement) : OverlayAnchor

    /** On top of an open screen that [screens] accepts, at [level]. The overlay takes the cursor of that screen. */
    data class OnScreen(val screens: ScreenMatch, val level: ScreenLevel) : OverlayAnchor
}

/** Where on an open screen an [OverlayAnchor.OnScreen] overlay is drawn. */
enum class ScreenLevel {
    /**
     * Over the background of the screen, the dimmed or blurred world, and under everything the screen draws on it.
     * The screen gets the pointer first wherever it has something to click.
     */
    BACKGROUND,

    /** Over what the screen draws, under its tooltips. */
    CONTENT,

    /** Over everything, tooltips included. */
    TOP,
}

/** Which open screens an [OverlayAnchor.OnScreen] overlay rises above. */
sealed interface ScreenMatch {
    /** Whether a screen of [type] is one of them. */
    fun matches(type: Class<*>): Boolean

    data object AnyScreen : ScreenMatch {
        override fun matches(type: Class<*>): Boolean = true
    }

    /**
     * Screens of the class with the binary name [className], as `override(...)` names it, and of its subclasses
     * when [subclasses] is set.
     */
    data class OfType(val className: String, val subclasses: Boolean) : ScreenMatch {
        override fun matches(type: Class<*>): Boolean =
            if (subclasses) generateSequence(type) { it.superclass }.any { it.name == className } else type.name == className
    }
}
