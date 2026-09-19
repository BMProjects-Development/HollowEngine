package ru.hollowhorizon.hollowengine.client.ui.ide

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import net.minecraft.client.Minecraft
import net.minecraft.world.Difficulty
import net.minecraft.world.level.GameType
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.cutscene.TimeOfDayValueFormatter
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownItem
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownMark
import ru.hollowhorizon.hollowengine.client.ui.widgets.UiDropdownSlider
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.network.RequestWorldControlPacket
import ru.hollowhorizon.hollowengine.common.network.WorldChange
import ru.hollowhorizon.hollowengine.common.network.WorldControlState
import ru.hollowhorizon.hollowengine.common.network.WorldControlStatePacket
import ru.hollowhorizon.hollowengine.common.network.WorldRule
import ru.hollowhorizon.hollowengine.common.network.WorldWeather

/** The server's answer to the World menu; the server stays the only one that changes anything. */
internal object WorldControlClient {
    var state by mutableStateOf<WorldControlState?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    /** Safe to call from composition, which may run off the main thread. */
    fun refresh() {
        Minecraft.getInstance().execute { RequestWorldControlPacket().send() }
    }

    fun change(change: WorldChange) {
        Minecraft.getInstance().execute { RequestWorldControlPacket(change).send() }
    }

    fun accept(packet: WorldControlStatePacket) {
        state = packet.state ?: state
        error = packet.error
    }
}

internal object WorldLang {
    private const val ROOT = "hollowengine.gui.ide.world."

    const val TITLE = ROOT + "title"
    const val LOADING = ROOT + "loading"
    const val TIME = ROOT + "time"
    const val DAYLIGHT_CYCLE = ROOT + "daylight_cycle"
    const val WEATHER_CYCLE = ROOT + "weather_cycle"
    const val DIFFICULTY = ROOT + "difficulty"
    const val DIFFICULTY_LOCKED = ROOT + "difficulty.locked"
    const val WEATHER = ROOT + "weather"
    const val GAME_MODE = ROOT + "game_mode"
    const val RULES = ROOT + "rules"
    const val TIME_MORNING = ROOT + "time.morning"
    const val TIME_NOON = ROOT + "time.noon"
    const val TIME_EVENING = ROOT + "time.evening"
    const val TIME_NIGHT = ROOT + "time.night"
    const val TIME_MIDNIGHT = ROOT + "time.midnight"

    fun weather(weather: WorldWeather): String = ROOT + "weather." + weather.name.lowercase()
}

private const val DayTicks = 24_000L

/** One in-game minute, the step of the time slider. */
private const val MinuteTicks = DayTicks / (24 * 60)

private val MenuRules = listOf(WorldRule.MOB_SPAWNING, WorldRule.MOB_GRIEFING, WorldRule.KEEP_INVENTORY, WorldRule.FIRE_TICK)

private class TimePreset(val labelKey: String, val ticks: Long)

private val TimePresets = listOf(
    TimePreset(WorldLang.TIME_MORNING, 0L),
    TimePreset(WorldLang.TIME_NOON, 6_000L),
    TimePreset(WorldLang.TIME_EVENING, 12_000L),
    TimePreset(WorldLang.TIME_NIGHT, 14_000L),
    TimePreset(WorldLang.TIME_MIDNIGHT, 18_000L),
)

/**
 * The World menu: one category per setting, each showing its current value and opening its
 * choices beside the menu. Entries stay open on click, so every change is visible right away.
 */
internal fun hollowIdeWorldMenuItems(): List<UiDropdownItem> {
    WorldControlClient.error?.let { return listOf(UiDropdownItem(it.lang, enabled = false)) }
    val state = WorldControlClient.state ?: return listOf(UiDropdownItem(WorldLang.LOADING.lang, enabled = false))

    fun rule(rule: WorldRule, label: String = rule.key.descriptionId.lang, separatorBefore: Boolean = false) =
        UiDropdownItem(
            label = label,
            checked = state.rules[rule] == true,
            mark = UiDropdownMark.CHECKBOX,
            closeOnClick = false,
            separatorBefore = separatorBefore,
        ) {
            WorldControlClient.change(WorldChange(rule = rule, ruleValue = state.rules[rule] != true))
        }

    fun radio(label: String, checked: Boolean, enabled: Boolean = true, change: () -> WorldChange) =
        UiDropdownItem(
            label = label,
            enabled = enabled,
            checked = checked,
            mark = UiDropdownMark.RADIO,
            closeOnClick = false,
        ) {
            if (!checked) WorldControlClient.change(change())
        }

    val timeOfDay = state.dayTime.mod(DayTicks)
    val difficulty = Difficulty.byId(state.difficulty)
    val gameMode = GameType.byId(state.gameMode)

    val time = buildList {
        add(
            UiDropdownItem(
                label = WorldLang.TIME.lang,
                slider = UiDropdownSlider(
                    value = timeOfDay.toFloat(),
                    min = 0f,
                    max = (DayTicks - 1).toFloat(),
                    step = MinuteTicks.toFloat(),
                    valueLabel = TimeOfDayValueFormatter::format,
                    onChange = { WorldControlClient.change(WorldChange(timeOfDay = it.toLong())) },
                ),
                closeOnClick = false,
            ),
        )
        TimePresets.forEachIndexed { index, preset ->
            add(
                UiDropdownItem(
                    label = preset.labelKey.lang,
                    shortcut = TimeOfDayValueFormatter.format(preset.ticks.toFloat()),
                    closeOnClick = false,
                    separatorBefore = index == 0,
                ) {
                    WorldControlClient.change(WorldChange(timeOfDay = preset.ticks))
                },
            )
        }
        add(rule(WorldRule.DAYLIGHT_CYCLE, WorldLang.DAYLIGHT_CYCLE.lang, separatorBefore = true))
    }

    val weather = WorldWeather.entries.map { option ->
        radio(WorldLang.weather(option).lang, state.weather == option) { WorldChange(weather = option) }
    } + rule(WorldRule.WEATHER_CYCLE, WorldLang.WEATHER_CYCLE.lang, separatorBefore = true)

    val gameModes = GameType.entries.map { mode ->
        radio(mode.shortDisplayName.string, mode == gameMode) { WorldChange(gameMode = mode.id) }
    }

    val difficulties = Difficulty.entries.map { option ->
        radio(option.displayName.string, option == difficulty, enabled = !state.difficultyLocked) {
            WorldChange(difficulty = option.id)
        }
    }

    return listOf(
        UiDropdownItem(WorldLang.TIME.lang, shortcut = TimeOfDayValueFormatter.format(timeOfDay.toFloat()), children = time),
        UiDropdownItem(WorldLang.WEATHER.lang, shortcut = WorldLang.weather(state.weather).lang, children = weather),
        UiDropdownItem(WorldLang.GAME_MODE.lang, shortcut = gameMode.shortDisplayName.string, children = gameModes),
        UiDropdownItem(
            WorldLang.DIFFICULTY.lang,
            shortcut = difficulty.displayName.string +
                if (state.difficultyLocked) " (" + WorldLang.DIFFICULTY_LOCKED.lang + ")" else "",
            children = difficulties,
        ),
        UiDropdownItem(WorldLang.RULES.lang, children = MenuRules.map { rule(it) }),
    )
}
