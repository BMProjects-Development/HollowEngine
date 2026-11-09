package ru.hollowhorizon.hollowengine.client.ui.ide.timeline

import kotlin.math.floor

/** Shows a day time in ticks as the clock on the wall: tick 0 is 06:00. */
object TimeOfDayValueFormatter : ChannelValueFormatter {
    const val DAY_TICKS = 24_000L

    override fun format(value: Float): String {
        val dayTicks = DAY_TICKS.toFloat()
        val normalized = ((value % dayTicks) + dayTicks) % dayTicks
        val minutesSinceSix = floor(normalized * MINUTES_PER_DAY / dayTicks).toInt()
        val totalMinutes = (minutesSinceSix + SIX_AM_MINUTES) % MINUTES_PER_DAY
        return "%02d:%02d".format(totalMinutes / MINUTES_PER_HOUR, totalMinutes % MINUTES_PER_HOUR)
    }

    private const val MINUTES_PER_HOUR = 60
    private const val MINUTES_PER_DAY = 24 * MINUTES_PER_HOUR
    private const val SIX_AM_MINUTES = 6 * MINUTES_PER_HOUR
}
