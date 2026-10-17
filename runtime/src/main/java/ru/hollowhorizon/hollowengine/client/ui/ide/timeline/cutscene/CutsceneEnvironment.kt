package ru.hollowhorizon.hollowengine.client.ui.ide.timeline.cutscene

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import ru.hollowhorizon.hollowengine.client.ui.ide.timeline.*
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

enum class CutsceneWeather(val value: Float, val labelKey: String) {
    CLEAR(0f, "hollowengine.gui.ide.cutscene.weather.clear"),
    RAIN(1f, "hollowengine.gui.ide.cutscene.weather.rain"),
    THUNDER(2f, "hollowengine.gui.ide.cutscene.weather.thunder");

    companion object {
        fun fromValue(value: Float): CutsceneWeather = entries.minBy { weather ->
            abs(weather.value - value)
        }
    }
}

object EnvironmentRig {
    const val WORLD_ID = "world"
    const val ENVIRONMENT_ID = "world.environment"
    const val TIME_OF_DAY_ID = "world.environment.time_of_day"
    const val WEATHER_ID = "world.environment.weather"

    const val DAY_TICKS = 24_000L
}

object TimeOfDayValueFormatter : ChannelValueFormatter {
    override fun format(value: Float): String {
        val dayTicks = EnvironmentRig.DAY_TICKS.toFloat()
        val normalized = ((value % dayTicks) + dayTicks) % dayTicks
        val minutesSinceSix = floor(normalized * MINUTES_PER_DAY / dayTicks).toInt()
        val totalMinutes = (minutesSinceSix + SIX_AM_MINUTES) % MINUTES_PER_DAY
        return "%02d:%02d".format(totalMinutes / MINUTES_PER_HOUR, totalMinutes % MINUTES_PER_HOUR)
    }

    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
    private const val SIX_AM_MINUTES = 6 * MINUTES_PER_HOUR
}

class TimeOfDayPropertyType : PropertyType<Float> {
    override val id = ID
    override val channels = listOf(
        ChannelSpec(
            name = "Time",
            color = ChannelColors.SCALAR,
            cyclePeriod = EnvironmentRig.DAY_TICKS.toFloat(),
            graphValueFormatter = TimeOfDayValueFormatter,
        ),
    )

    override fun decompose(value: Float, into: FloatArray) {
        into[0] = value
    }

    override fun compose(values: FloatArray): Float = values[0]

    companion object {
        const val ID = "time_of_day"
    }
}

class WeatherPropertyType : PropertyType<CutsceneWeather> {
    override val id = ID
    override val channels = listOf(
        ChannelSpec(
            name = "Weather",
            color = ChannelColors.SCALAR,
            sampling = ChannelSampling.DISCRETE,
            valueOptions = CutsceneWeather.entries.map { weather ->
                ChannelValueOption(weather.value, weather.labelKey)
            },
        ),
    )
    override val isChannelSpaceLinear = false

    override fun decompose(value: CutsceneWeather, into: FloatArray) {
        into[0] = value.value
    }

    override fun compose(values: FloatArray): CutsceneWeather = CutsceneWeather.fromValue(values[0])

    companion object {
        const val ID = "weather"
    }
}

data class CutsceneEnvironment(
    val timeOfDay: Float? = null,
    val weather: CutsceneWeather? = null,
) {
    val isEmpty: Boolean get() = timeOfDay == null && weather == null
}

internal fun ClientLevel.captureCutsceneEnvironment(): CutsceneEnvironment = CutsceneEnvironment(
    timeOfDay = Math.floorMod(dayTime, EnvironmentRig.DAY_TICKS).toFloat(),
    weather = when {
        getThunderLevel(1f) > WEATHER_THRESHOLD -> CutsceneWeather.THUNDER
        getRainLevel(1f) > WEATHER_THRESHOLD -> CutsceneWeather.RAIN
        else -> CutsceneWeather.CLEAR
    },
)

internal class CutsceneEnvironmentOverride {
    private var timeSnapshot: TimeSnapshot? = null
    private var weatherSnapshot: WeatherSnapshot? = null

    fun apply(level: ClientLevel, environment: CutsceneEnvironment) {
        applyTime(level, environment.timeOfDay)
        applyWeather(level, environment.weather)
    }

    fun restore() {
        restoreTime()
        restoreWeather()
    }

    private fun applyTime(level: ClientLevel, value: Float?) {
        if (value == null) {
            restoreTime()
            return
        }
        val snapshot = timeSnapshot?.takeIf { it.level === level } ?: run {
            restoreTime()
            TimeSnapshot(level, level.gameTime, level.dayTime).also { timeSnapshot = it }
        }
        val dayBase = Math.floorDiv(snapshot.dayTime, EnvironmentRig.DAY_TICKS) * EnvironmentRig.DAY_TICKS
        level.setDayTime(dayBase + value.roundToLong())
    }

    private fun applyWeather(level: ClientLevel, weather: CutsceneWeather?) {
        if (weather == null) {
            restoreWeather()
            return
        }
        if (weatherSnapshot?.level !== level) {
            restoreWeather()
            weatherSnapshot = WeatherSnapshot(level, level.getRainLevel(1f), level.getThunderLevel(1f))
        }
        when (weather) {
            CutsceneWeather.CLEAR -> setWeather(level, rain = 0f, thunder = 0f)
            CutsceneWeather.RAIN -> setWeather(level, rain = 1f, thunder = 0f)
            CutsceneWeather.THUNDER -> setWeather(level, rain = 1f, thunder = 1f)
        }
    }

    private fun restoreTime() {
        val snapshot = timeSnapshot ?: return
        if (Minecraft.getInstance().level === snapshot.level) {
            val elapsedTicks = max(0L, snapshot.level.gameTime - snapshot.gameTime)
            snapshot.level.setDayTime(snapshot.dayTime + elapsedTicks)
        }
        timeSnapshot = null
    }

    private fun restoreWeather() {
        val snapshot = weatherSnapshot ?: return
        if (Minecraft.getInstance().level === snapshot.level) {
            setWeather(snapshot.level, snapshot.rain, snapshot.thunder)
        }
        weatherSnapshot = null
    }

    private fun setWeather(level: ClientLevel, rain: Float, thunder: Float) {
        level.setRainLevel(rain)
        level.setThunderLevel(thunder)
    }

    private data class TimeSnapshot(val level: ClientLevel, val gameTime: Long, val dayTime: Long)
    private data class WeatherSnapshot(val level: ClientLevel, val rain: Float, val thunder: Float)
}

private const val WEATHER_THRESHOLD = 0.01f
