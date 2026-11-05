package ru.hollowhorizon.hollowengine.addons.narrative.story

import kotlinx.coroutines.delay
import kotlinx.serialization.KSerializer
import net.minecraft.nbt.CompoundTag
import ru.hollowhorizon.hollowengine.common.utils.currentServer
import java.lang.Math.addExact
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The data of the segment the current stage is in: since the last stage call or checkpoint. It is saved with
 * the story and starts empty at the next boundary, so it remembers what a segment already did when the segment
 * repeats after a restart.
 */
suspend fun segmentTag(): CompoundTag = currentFrame("segmentTag()").segment

/**
 * Runs [block] once for the whole story: the segment repeated after a restart or a [rewind] skips it, so a reward is
 * not given twice. [name] belongs to the segment it is called in, of a stage called with these arguments; inside a
 * loop, put the index into it.
 */
suspend fun once(name: String, block: suspend () -> Unit) {
    val frame = currentFrame("once(\"$name\")")
    val done = frame.run.records.getCompound(ONCE)
    val key = frame.recordKey(name)
    if (done.contains(key)) return
    block()
    done.putBoolean(key, true)
    frame.run.records.put(ONCE, done)
}

/** Computes a value once for the whole story, like [once]: a repeated segment gets the value computed the first time. */
suspend inline fun <reified T> remember(name: String, noinline block: suspend () -> T): T =
    remember(name, storySerializer<T>(), block)

@PublishedApi
@Suppress("UNCHECKED_CAST")
internal suspend fun <T> remember(name: String, serializer: KSerializer<Any?>, block: suspend () -> T): T {
    val frame = currentFrame("remember(\"$name\")")
    val values = frame.run.records.getCompound(REMEMBERED)
    val key = frame.recordKey(name)
    values.get(key)?.let { return StoryValues.decode(serializer, it) as T }
    val value = block()
    StoryValues.put(values, key, serializer, value)
    frame.run.records.put(REMEMBERED, values)
    return value
}

/** Forgets what [remember] or [once] kept under [name] in this segment; returns whether there was something. */
suspend fun forget(name: String): Boolean {
    val frame = currentFrame("forget(\"$name\")")
    val key = frame.recordKey(name)
    val records = frame.run.records
    val existed = listOf(ONCE, REMEMBERED).count { kind ->
        val tag = records.getCompound(kind)
        tag.contains(key).also { if (it) tag.remove(key) }
    } > 0
    return existed
}

/** Waits for loaded server ticks; a segment repeated after a restart continues the same countdown. */
suspend fun wait(ticks: Int, name: String) {
    val frame = currentFrame("wait(ticks, \"$name\")")
    waitPersistent(frame.segment, name, ticks, { currentServer.overworld().gameTime }) { delay(TICK) }
}

/** Waits for wall-clock time, counting the time the game was not running. */
suspend fun wait(duration: Duration, name: String) {
    require(duration.isFinite() && duration >= Duration.ZERO) { "Realtime wait duration must be finite and non-negative" }
    wait(Instant.ofEpochMilli(addExact(System.currentTimeMillis(), duration.inWholeMilliseconds)), name)
}

/** Waits until [instant]; the deadline set the first time is kept across restarts. */
suspend fun wait(instant: Instant, name: String) {
    val frame = currentFrame("wait(instant, \"$name\")")
    waitUntil(frame.segment, name, instant.toEpochMilli(), System::currentTimeMillis) { delay(it.milliseconds) }
}

internal suspend fun waitPersistent(
    tag: CompoundTag,
    name: String,
    ticks: Int,
    currentTick: () -> Long,
    pause: suspend () -> Unit,
) {
    require(ticks >= 0) { "Persistent wait duration cannot be negative" }
    val key = waitKey(GAME_TIME, name)
    val deadline = if (tag.contains(key)) tag.getLong(key) else addExact(currentTick(), ticks.toLong()).also { tag.putLong(key, it) }
    while (currentTick() < deadline) pause()
    tag.remove(key)
}

internal suspend fun waitUntil(
    tag: CompoundTag,
    name: String,
    requestedDeadline: Long,
    nowMillis: () -> Long,
    pause: suspend (Long) -> Unit,
) {
    val key = waitKey(REAL_TIME, name)
    val deadline = if (tag.contains(key)) tag.getLong(key) else requestedDeadline.also { tag.putLong(key, it) }
    while (true) {
        val remaining = deadline - nowMillis()
        if (remaining <= 0L) break
        pause(minOf(remaining, MAX_REALTIME_SLEEP_MILLIS))
    }
    tag.remove(key)
}

private suspend fun currentFrame(what: String): StoryFrame =
    currentRun(what).frames.lastOrNull() ?: throw StoryException("$what must be called from a stage")

private fun waitKey(kind: String, name: String): String {
    require(name.isNotBlank()) { "Persistent wait name cannot be blank" }
    return "wait$.$kind.$name"
}

private const val ONCE = "once"
private const val REMEMBERED = "remembered"
private const val GAME_TIME = "game"
private const val REAL_TIME = "real"
private const val MAX_REALTIME_SLEEP_MILLIS = 60_000L
private val TICK = 50.milliseconds
