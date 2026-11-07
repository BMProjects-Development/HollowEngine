package ru.hollowhorizon.hollowengine.common.time

import kotlin.time.Duration
import kotlin.time.DurationUnit
import kotlin.time.toDuration

val TICK: Duration = 50.toDuration(DurationUnit.MILLISECONDS)

val Int.ticks: Duration get() = TICK * this
val Double.ticks: Duration get() = TICK * this

val Int.ms: Duration get() = toDuration(DurationUnit.MILLISECONDS)
val Double.ms: Duration get() = toDuration(DurationUnit.MILLISECONDS)

val Int.sec: Duration get() = toDuration(DurationUnit.SECONDS)
val Double.sec: Duration get() = toDuration(DurationUnit.SECONDS)

val Int.min: Duration get() = toDuration(DurationUnit.MINUTES)
val Double.min: Duration get() = toDuration(DurationUnit.MINUTES)

val Int.hours: Duration get() = toDuration(DurationUnit.HOURS)
val Double.hours: Duration get() = toDuration(DurationUnit.HOURS)

/** How many whole game ticks this lasts. */
val Duration.inWholeTicks: Long get() = inWholeMilliseconds / TICK.inWholeMilliseconds
