package ru.hollowhorizon.hollowengine.addons.narrative.ide

import ru.hollowhorizon.hollowengine.addons.narrative.story.StoryCategory
import ru.hollowhorizon.hollowengine.client.HighlightTheme
import ru.hollowhorizon.hollowengine.client.ui.UiColor

class StoryCategoryStyle(val color: UiColor, val icon: String?)

object StoryCategories {
    const val PREFIX = StoryCategory.PREFIX
    private const val OPAQUE = 0xFF shl 24
    private const val HUES = 360
    private const val DIMMED = 0.6f
    private const val LIT = 0.92f

    private const val FIRST = -100
    private const val LAST = 100

    private val styles = HashMap<String, StoryCategoryStyle>()
    private val orders = HashMap<String, Int>()

    init {
        builtin(StoryCategory.ANIMATION, 0xB5577F, "film")
        builtin(StoryCategory.CAMERA, 0x4E7FB0, "timeline/capture")
        builtin(StoryCategory.DIALOGUE, 0x3B8950, "story/phrase", NARRATIVE_ICONS)
        builtin(StoryCategory.EFFECTS, 0xA0569E, "vfx/particle")
        builtin(StoryCategory.MATERIALS, 0x8F5FA8, "vfx/material")
        builtin(StoryCategory.SCREEN, 0x9C5A86, "vfx/post")
        builtin(StoryCategory.SOUND, 0xA0569E, "volume")
        builtin(StoryCategory.FLOW, 0x5F59B1, "story/block", NARRATIVE_ICONS)
        builtin(StoryCategory.ITEMS, 0xA86E3A, "recipes")
        builtin(StoryCategory.STORY, 0xCF5E54, "story/label", NARRATIVE_ICONS)
        builtin(StoryCategory.VARIABLES, 0x6A7590, "variables")
        builtin(StoryCategory.WAIT, 0x3E86C4, "story/category/time", NARRATIVE_ICONS)
        builtin(StoryCategory.VALUES, 0x2F7D80, "math")
        builtin(StoryCategory.LIST, 0x5A8A3C, "layers")
        builtin(StoryCategory.LOGIC, 0x7A5FB5, "logic")
        builtin(StoryCategory.MATH, 0x2F7D80, "math")
        builtin(StoryCategory.TEXT, 0x9A7A3A, "files/text")
        builtin(StoryCategory.VECTOR, 0x4F6FC0, "graph/coordinates")
        builtin(StoryCategory.WORLD, 0x5E8C3A, "world")
        builtin(StoryCategory.BLOCKS, 0x7C6A3E, "files/blocks")
        builtin(StoryCategory.WEATHER, 0x4F8FB8, "graph/sun")
        builtin(StoryCategory.ENTITIES, 0x9A6A48, "general")
        builtin(StoryCategory.DATA, 0x7A7040, "story/category/data", NARRATIVE_ICONS)
        builtin(StoryCategory.NBT, 0x5F8F80, "story/category/data", NARRATIVE_ICONS)
        builtin(StoryCategory.ENUMS, 0x6A7FA0, "types")
        builtin(StoryCategory.TYPES, 0x6A7FA0, "types")
        builtin(StoryCategory.LEVELS, 0x5E8C3A, "world")
        builtin(StoryCategory.INVENTORY, 0xA86E3A, "recipes")
        builtin(StoryCategory.EQUIPMENT, 0xA86E3A, "recipes")
        builtin(StoryCategory.TIME, 0x4F8FB8, "graph/sun")
        order(StoryCategory.WORLD, FIRST)
        order(StoryCategory.STORY, FIRST + 1)
        order(StoryCategory.VARIABLES, LAST - 1)
        order(StoryCategory.VALUES, LAST)
        builtin(StoryCategory.PLAYERS, 0x4F7FA8, "players")
        builtin(StoryCategory.NPC, 0xB0683E, "story/category/npc", NARRATIVE_ICONS)
        builtin(StoryCategory.NAVIGATION, 0x3A8FA0, "story/category/navigation", NARRATIVE_ICONS)
        builtin(StoryCategory.COMPONENTS, 0x6F7F5A, "types")
    }

    /** Gives the category of [key] its own [color], `0xRRGGBB`, and the [icon] of its nodes. */
    fun register(key: String, color: Int, icon: String? = null) {
        styles[key] = StoryCategoryStyle(UiColor.fromArgb(OPAQUE or color), icon)
    }

    /** Puts the category [key] before the others of its level when [rank] is below 0, after them when it is above. */
    fun order(key: String, rank: Int) {
        orders[key] = rank
    }

    /** Where the category [key] stands among those of its level; the ones of the same rank go by name. */
    fun rank(key: String): Int = orders[key] ?: 0

    fun color(key: String): UiColor = style(key)?.color ?: worked(root(key))

    fun icon(key: String): String? = style(key)?.icon

    /** [color] raised to stand out as an icon on the dark of a menu, keeping its hue. */
    fun tint(color: UiColor): UiColor {
        val scale = LIT / maxOf(color.red, color.green, color.blue, 1e-3f)
        return UiColor(color.red * scale, color.green * scale, color.blue * scale, color.alpha)
    }

    /** The category [key] sits under in the menu: `world.entities` for `world.entities.players`, none for a top one. */
    fun parent(key: String): String? {
        val name = key.removePrefix(PREFIX)
        return if (name != key && '.' in name) PREFIX + name.substringBeforeLast('.') else null
    }

    private fun style(key: String): StoryCategoryStyle? {
        var at: String? = key
        while (at != null) {
            styles[at]?.let { return it }
            at = parent(at)
        }
        return null
    }

    private fun root(key: String): String = parent(key)?.let(::root) ?: key

    private fun worked(key: String): UiColor {
        val argb = HighlightTheme.paletteArgb(Math.floorMod(key.hashCode(), HUES))
        return UiColor(channel(argb shr 16), channel(argb shr 8), channel(argb))
    }

    private fun channel(value: Int) = (value and 0xFF) / 255f * DIMMED

    private fun builtin(key: String, color: Int, icon: String, icons: String = ENGINE_ICONS) =
        register(key, color, "$icons$icon.svg")
}

private const val ENGINE_ICONS = "hollowengine:textures/gui/icons/"
private const val NARRATIVE_ICONS = "hollowengine-narrative:textures/gui/icons/"
