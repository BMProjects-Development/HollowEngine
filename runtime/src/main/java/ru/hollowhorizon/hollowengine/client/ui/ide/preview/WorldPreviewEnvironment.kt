package ru.hollowhorizon.hollowengine.client.ui.ide.preview

import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.level.Level
import ru.hollowhorizon.hollowengine.bridge.mixins.client.LevelWeatherAccessor

/** Environment overrides for a single view of the world. Time is measured in ticks from the start of the day. */
data class WorldPreviewEnvironment(val timeOfDay: Long? = null, val weather: WorldPreviewWeather? = null)

enum class WorldPreviewWeather(val rain: Float, val thunder: Float) {
    CLEAR(0f, 0f),
    RAIN(1f, 0f),
    THUNDER(1f, 1f);
}

internal fun WorldPreviewEnvironment.apply(level: ClientLevel): (() -> Unit)? {
    if (timeOfDay == null && weather == null) return null
    val previousTime = level.dayTime
    val previousWeather = weather?.let { WeatherSnapshot(level as LevelWeatherAccessor) }
    if (timeOfDay != null) {
        val dayTicks = Level.TICKS_PER_DAY.toLong()
        val dayBase = Math.floorDiv(previousTime, dayTicks) * dayTicks
        level.levelData.dayTime = dayBase + timeOfDay
    }
    if (weather != null) {
        level.setRainLevel(weather.rain)
        level.setThunderLevel(weather.thunder)
    }
    return {
        if (timeOfDay != null) level.levelData.dayTime = previousTime
        previousWeather?.restore(level)
    }
}

private class WeatherSnapshot(private val state: LevelWeatherAccessor) {
    private val rain = state.`hollowengine$getRainLevel`()
    private val oldRain = state.`hollowengine$getPreviousRainLevel`()
    private val thunder = state.`hollowengine$getThunderLevel`()
    private val oldThunder = state.`hollowengine$getPreviousThunderLevel`()

    fun restore(level: ClientLevel) {
        level.setRainLevel(rain)
        level.setThunderLevel(thunder)
        state.`hollowengine$setPreviousRainLevel`(oldRain)
        state.`hollowengine$setPreviousThunderLevel`(oldThunder)
    }
}
