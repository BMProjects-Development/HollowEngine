package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

import kotlinx.coroutines.delay
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.number
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.string
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.effects.playSound
import kotlin.time.Duration.Companion.milliseconds

/**
 * The functions every story can call without an addon registering anything. Deliberately few: the
 * language ships the control flow, and the engine ships only what a story cannot express itself.
 */
internal object StoryBuiltinFunctions {
    /** Names this object owns. Dropped before re-registering, so installing twice is harmless. */
    private val OWNED = listOf("wait", "play-sound")

    fun install(registry: StoryFunctionRegistry) {
        OWNED.forEach(registry::unregister)
        registry.waitFunction()
        registry.soundFunctions()
    }

    /**
     * `@wait 2s`, a pause that survives a restart. The deadline goes into the call's own checkpoint,
     * so a server that comes back after three seconds does not make the players wait again.
     */
    private fun StoryFunctionRegistry.waitFunction() {
        add("wait", number("time")) { args ->
            val deadline = if (state.contains(DEADLINE_KEY)) {
                state.getLong(DEADLINE_KEY)
            } else {
                (System.currentTimeMillis() + args.millis("time")).also { state.putLong(DEADLINE_KEY, it) }
            }
            while (true) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                delay(minOf(remaining, MAX_SLEEP_MILLIS).milliseconds)
            }
            state.remove(DEADLINE_KEY)
        }
    }

    /** `@play-sound minecraft:block.note_block.bell 1.0`, heard by every participant. */
    private fun StoryFunctionRegistry.soundFunctions() {
        add("play-sound", string("name")) { args ->
            players.forEach { it.playSound(args.string("name")) }
        }
        add("play-sound", string("name"), number("volume")) { args ->
            players.forEach { it.playSound(args.string("name"), args.number("volume")) }
        }
        add("play-sound", string("name"), number("volume"), number("pitch")) { args ->
            players.forEach { it.playSound(args.string("name"), args.number("volume"), args.number("pitch")) }
        }
    }

    private const val DEADLINE_KEY = "deadline"
    private const val MAX_SLEEP_MILLIS = 1_000L
}
