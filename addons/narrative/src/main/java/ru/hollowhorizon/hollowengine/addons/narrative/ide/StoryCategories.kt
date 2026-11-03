package ru.hollowhorizon.hollowengine.addons.narrative.ide

import ru.hollowhorizon.hollowengine.client.HighlightTheme
import ru.hollowhorizon.hollowengine.client.ui.UiColor

class StoryCategoryStyle(val color: UiColor, val icon: String?)

object StoryCategories {
    const val PREFIX = "hollowengine.gui.ide.story.category."
    private const val OPAQUE = 0xFF shl 24
    private const val HUES = 360
    private const val DIMMED = 0.6f
    private const val LIT = 0.92f

    private val styles = HashMap<String, StoryCategoryStyle>()

    init {
        builtin("animation", 0xB5577F, "film")
        builtin("camera", 0x4E7FB0, "timeline/capture")
        builtin("data", 0x7A7040, "story/category/data", NARRATIVE_ICONS)
        builtin("dialogue", 0x3B8950, "story/phrase", NARRATIVE_ICONS)
        builtin("effects", 0xA0569E, "vfx/particle")
        builtin("effects.materials", 0x8F5FA8, "vfx/material")
        builtin("effects.screen", 0x9C5A86, "vfx/post")
        builtin("effects.sound", 0xA0569E, "volume")
        builtin("flow", 0x5F59B1, "story/block", NARRATIVE_ICONS)
        builtin("items", 0xA86E3A, "recipes")
        builtin("list", 0x5A8A3C, "layers")
        builtin("logic", 0x7A5FB5, "logic")
        builtin("math", 0x2F7D80, "math")
        builtin("navigation", 0x3A8FA0, "story/category/navigation", NARRATIVE_ICONS)
        builtin("npc", 0xB0683E, "story/category/npc", NARRATIVE_ICONS)
        builtin("story", 0xCF5E54, "story/label", NARRATIVE_ICONS)
        builtin("text", 0x9A7A3A, "files/text")
        builtin("time", 0x3E86C4, "story/category/time", NARRATIVE_ICONS)
        builtin("tracks", 0x4F9A78, "star")
        builtin("variables", 0x6A7590, "variables")
        builtin("vector", 0x4F6FC0, "graph/coordinates")
        builtin("world", 0x5E8C3A, "world")
    }

    /** Gives the category of [key] its own [color], `0xRRGGBB`, and the [icon] of its nodes. */
    fun register(key: String, color: Int, icon: String? = null) {
        styles[key] = StoryCategoryStyle(UiColor.fromArgb(OPAQUE or color), icon)
    }

    fun color(key: String): UiColor = style(key)?.color ?: worked(root(key))

    fun icon(key: String): String? = style(key)?.icon

    /** [color] raised to stand out as an icon on the dark of a menu, keeping its hue. */
    fun tint(color: UiColor): UiColor {
        val scale = LIT / maxOf(color.red, color.green, color.blue, 1e-3f)
        return UiColor(color.red * scale, color.green * scale, color.blue * scale, color.alpha)
    }

    private fun style(key: String): StoryCategoryStyle? {
        var at: String? = key
        while (at != null) {
            styles[at]?.let { return it }
            at = parent(at)
        }
        return null
    }

    private fun parent(key: String): String? {
        val name = key.removePrefix(PREFIX)
        return if (name != key && '.' in name) PREFIX + name.substringBeforeLast('.') else null
    }

    private fun root(key: String): String = parent(key)?.let(::root) ?: key

    private fun worked(key: String): UiColor {
        val argb = HighlightTheme.paletteArgb(Math.floorMod(key.hashCode(), HUES))
        return UiColor(channel(argb shr 16), channel(argb shr 8), channel(argb))
    }

    private fun channel(value: Int) = (value and 0xFF) / 255f * DIMMED

    private fun builtin(name: String, color: Int, icon: String, icons: String = ENGINE_ICONS) =
        register(PREFIX + name, color, "$icons$icon.svg")
}

private const val ENGINE_ICONS = "hollowengine:textures/gui/icons/"
private const val NARRATIVE_ICONS = "hollowengine-narrative:textures/gui/icons/"
