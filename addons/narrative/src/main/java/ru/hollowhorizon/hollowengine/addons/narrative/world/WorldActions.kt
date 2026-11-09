package ru.hollowhorizon.hollowengine.addons.narrative.world

import net.minecraft.core.BlockPos
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ServerLevelData
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.narrative.story.*
import ru.hollowhorizon.hollowengine.common.attachments.editor.EditorCategory
import ru.hollowhorizon.hollowengine.common.utils.currentServer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** What the sky does. */
enum class Weather {
    CLEAR, RAIN, THUNDER,
}

/**
 * Puts [block] at [pos] of [level], the level of the story when left out. Undone, the block there is as it was,
 * with what its chest or sign held.
 */
@EditorCategory(StoryCategory.BLOCKS)
@Action("set-block")
suspend fun setBlock(pos: Vec3, block: BlockState, level: ServerLevel? = null) {
    val where = level ?: storyLevel()
    action(pos, block, level) {
        val at = BlockPos.containing(pos)
        val before = capture { BlockSnapshot.of(where, listOf(at)) }
        undo { BlockSnapshot.restore(before) }
        perform { BlockSnapshot.place(where, at, block) }
    }
}

/** Breaks the block at [pos], dropping it with [drop]. Undone, the block is back, and what dropped stays. */
@EditorCategory(StoryCategory.BLOCKS)
@Action("break-block")
suspend fun breakBlock(pos: Vec3, drop: Boolean = false, level: ServerLevel? = null) {
    val where = level ?: storyLevel()
    action(pos, drop, level) {
        val at = BlockPos.containing(pos)
        val before = capture { BlockSnapshot.of(where, listOf(at)) }
        undo { BlockSnapshot.restore(before) }
        perform { where.destroyBlock(at, drop) }
    }
}

/** Fills the box between [from] and [to] with [block], as `/fill` does. Undone, every block of it is as it was. */
@EditorCategory(StoryCategory.BLOCKS)
@Action("fill")
suspend fun fillBlocks(from: Vec3, to: Vec3, block: BlockState, level: ServerLevel? = null) {
    val where = level ?: storyLevel()
    action(from, to, block, level) {
        val box = BlockPos.betweenClosed(BlockPos.containing(from), BlockPos.containing(to)).map(BlockPos::immutable)
        if (box.size > MAX_FILL) throw StoryException("A fill takes $MAX_FILL blocks at most, not ${box.size}")
        val before = capture { BlockSnapshot.of(where, box) }
        undo { BlockSnapshot.restore(before) }
        perform { box.forEach { BlockSnapshot.place(where, it, block) } }
    }
}

/** Turns the sky to [weather] for [time]. Undone, the weather is as it was, with as long left of it. */
@EditorCategory(StoryCategory.WEATHER)
@Action("set-weather")
suspend fun setWeather(weather: Weather, time: Duration = 5.minutes) = action(weather, time) {
    val level = currentServer.overworld()
    val data = level.levelData as ServerLevelData
    val before = capture {
        CompoundTag().apply {
            putInt(CLEAR_TIME, data.clearWeatherTime)
            putInt(RAIN_TIME, data.rainTime)
            putInt(THUNDER_TIME, data.thunderTime)
            putBoolean(RAINING, data.isRaining)
            putBoolean(THUNDERING, data.isThundering)
        }
    }
    undo {
        data.clearWeatherTime = before.getInt(CLEAR_TIME)
        data.rainTime = before.getInt(RAIN_TIME)
        data.thunderTime = before.getInt(THUNDER_TIME)
        data.isRaining = before.getBoolean(RAINING)
        data.isThundering = before.getBoolean(THUNDERING)
    }
    perform {
        val ticks = (time.inWholeMilliseconds / 50L).toInt()
        when (weather) {
            Weather.CLEAR -> level.setWeatherParameters(ticks, 0, false, false)
            Weather.RAIN -> level.setWeatherParameters(0, ticks, true, false)
            Weather.THUNDER -> level.setWeatherParameters(0, ticks, true, true)
        }
    }
}

/**
 * Turns the time of day of every dimension to [time], from 0 at sunrise to 24000, as `/time set` does; the clock only
 * goes forward, to the next such time. Undone, every dimension has the time it had.
 */
@EditorCategory(StoryCategory.TIME)
@Action("set-time")
suspend fun setTimeOfDay(time: Int) = action(time) {
    val before = capture {
        CompoundTag().apply {
            currentServer.allLevels.forEach {
                putLong(
                    it.dimension().location().toString(),
                    it.dayTime
                )
            }
        }
    }
    undo {
        before.allKeys.forEach { dimension -> BlockSnapshot.levelOf(dimension).dayTime = before.getLong(dimension) }
    }
    perform {
        currentServer.allLevels.forEach { level ->
            val day = level.dayTime - level.dayTime.mod(DAY)
            val next = day + time.toLong().mod(DAY)
            level.dayTime = if (next < level.dayTime) next + DAY else next
        }
    }
}

private const val MAX_FILL = 32768
private const val DAY = 24000L
private const val CLEAR_TIME = "clear"
private const val RAIN_TIME = "rain"
private const val THUNDER_TIME = "thunder"
private const val RAINING = "raining"
private const val THUNDERING = "thundering"
