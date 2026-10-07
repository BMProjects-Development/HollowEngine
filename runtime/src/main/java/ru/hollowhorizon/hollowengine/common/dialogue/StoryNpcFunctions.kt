package ru.hollowhorizon.hollowengine.common.dialogue

import kotlinx.coroutines.withTimeoutOrNull
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.common.dialogue.lang.actor
import ru.hollowhorizon.hollowengine.common.dialogue.lang.any
import ru.hollowhorizon.hollowengine.common.dialogue.lang.list
import ru.hollowhorizon.hollowengine.common.dialogue.lang.number
import ru.hollowhorizon.hollowengine.common.dialogue.lang.string
import ru.hollowhorizon.hollowengine.common.entities.NpcEntity
import ru.hollowhorizon.hollowengine.common.models.AnimationPlayMode
import ru.hollowhorizon.hollowengine.common.npcs.navigation.Facing
import ru.hollowhorizon.hollowengine.common.npcs.navigation.MoveOptions
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.entities.play
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.entities.playAndWait
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.entities.stopAnimation
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.npcs.clearLookTarget
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.npcs.lookAt
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.npcs.move
import ru.hollowhorizon.hollowengine.common.scripting.story.functions.npcs.stopMoving
import kotlin.time.Duration.Companion.milliseconds

/**
 * Story commands that drive the characters a dialogue was given, over the engine's own NPC API.
 */
internal object StoryNpcFunctions {
    private val OWNED = listOf(
        "walk-to", "stop-walking", "look-at", "stop-looking",
        "play-once", "play-looped", "play-clamped", "play-ping-pong", "stop-animation",
    )

    fun install(registry: StoryFunctionRegistry) {
        OWNED.forEach(registry::unregister)
        registry.movement()
        registry.looking()
        registry.animations()
    }

    private fun StoryFunctionRegistry.movement() {
        add(
            "walk-to",
            actor("who"), list("position"),
            number("speed", default = DEFAULT_SPEED), number("distance", default = DEFAULT_DISTANCE),
            number("timeout", default = DEFAULT_WALK_TIMEOUT_MILLIS),
            any("facing", optional = true),
        ) { args ->
            args.walk(args.actor("who").name) { npc ->
                val points = args.points("position")
                if (points != null) npc.move(points, args.moveOptions()) else npc.move(args.vec3("position"), args.moveOptions())
            }
        }

        add(
            "walk-to",
            actor("who"), actor("target"),
            number("speed", default = DEFAULT_SPEED), number("distance", default = DEFAULT_DISTANCE),
            number("timeout", default = DEFAULT_WALK_TIMEOUT_MILLIS),
            any("facing", optional = true),
        ) { args ->
            args.walk(args.actor("who").name) { npc -> npc.move(args.entity("target"), args.moveOptions()) }
        }

        add("stop-walking", actor("who")) { args -> args.npc("who").stopMoving() }
    }

    /**
     * Runs a walk with a deadline. Navigation retries an unreachable target forever by design, that
     * is what an NPC that must eventually get there wants, but a dialogue cannot afford it: the
     * story would sit on that statement with no window, no menu and no way out. Giving up is logged
     * and the story moves on; `timeout=0` restores the wait-forever behavior for a script that
     * really means it.
     */
    private suspend fun StoryArguments.walk(who: String, block: suspend (NpcEntity) -> Unit) {
        val npc = npc("who")
        val timeout = millis("timeout")
        if (timeout <= 0L) {
            block(npc)
            return
        }
        if (withTimeoutOrNull(timeout.milliseconds) { block(npc) } == null) {
            npc.stopMoving()
            HollowEngine.LOGGER.warn(
                "'@walk-to' gave up after {} ms: '{}' could not reach its target, the story continues without it",
                timeout,
                who,
            )
        }
    }

    private fun StoryFunctionRegistry.looking() {
        add("look-at", actor("who"), list("position"), number("time", default = DEFAULT_LOOK_MILLIS)) { args ->
            args.npc("who").lookAt(args.vec3("position"), args.lookDuration())
        }

        add("look-at", actor("who"), actor("target"), number("time", default = DEFAULT_LOOK_MILLIS)) { args ->
            args.npc("who").lookAt(args.entity("target"))
        }

        add("stop-looking", actor("who")) { args -> args.npc("who").clearLookTarget() }
    }

    private fun StoryFunctionRegistry.animations() {
        add("play-once", actor("who"), string("animation"), number("fade", default = DEFAULT_FADE_MILLIS.toFloat())) { args ->
            args.entity("who").playAndWait(args.string("animation"), args.fade(), args.fade())
        }

        addPlayMode("play-looped", AnimationPlayMode.Loop)
        addPlayMode("play-clamped", AnimationPlayMode.ClampForever)
        addPlayMode("play-ping-pong", AnimationPlayMode.PingPong)

        add("stop-animation", actor("who"), string("animation"), number("fade", default = DEFAULT_FADE_MILLIS.toFloat())) { args ->
            args.entity("who").stopAnimation(args.string("animation"), args.fade())
        }
    }

    private fun StoryFunctionRegistry.addPlayMode(name: String, mode: AnimationPlayMode) {
        add(name, actor("who"), string("animation"), number("fade", default = DEFAULT_FADE_MILLIS.toFloat())) { args ->
            args.entity("who").play(args.string("animation"), mode, args.fade(), args.fade())
        }
    }

    private fun StoryArguments.npc(name: String): NpcEntity {
        val entity: LivingEntity = entity(name)
        return entity as? NpcEntity
            ?: throw IllegalArgumentException(
                "'${actor(name).name}' is a ${entity.type.description.string}, not an engine NPC, so it cannot be told where to go",
            )
    }

    private fun StoryArguments.moveOptions() = MoveOptions(
        speed = number("speed").toDouble(),
        arrivalDistance = number("distance").toDouble(),
        facing = facing(),
    )

    /** The points of a position written `[[x, y, z], [x, y, z]]`, to walk through in turn; null for one `[x, y, z]`. */
    private fun StoryArguments.points(name: String): List<Vec3>? {
        val items = list(name)
        if (items.firstOrNull() !is StoryList) return null
        return items.map { item ->
            val numbers = (item as? StoryList)?.values?.map { (it as? StoryNumber)?.value?.toDouble() }
            require(numbers != null && numbers.size == 3 && numbers.all { it != null }) {
                "Argument '$name' must be positions like [[10, 64, 20], [15, 64, 20]]"
            }
            Vec3(numbers[0]!!, numbers[1]!!, numbers[2]!!)
        }
    }

    /** What `facing=` names to keep facing on the way: a character, or a position like [10, 64, 20]. */
    private fun StoryArguments.facing(): Facing = when {
        this["facing"] == null -> Facing.Path
        actorOrNull("facing") != null -> Facing.at(entity("facing"))
        else -> Facing.at(vec3("facing"))
    }

    private fun StoryArguments.lookDuration() = millis("time").milliseconds

    private fun StoryArguments.fade(): Double = number("fade").toDouble() / 1000.0

    private const val DEFAULT_LOOK_MILLIS = 1_500f
    private const val DEFAULT_FADE_MILLIS = 330f

    private const val DEFAULT_WALK_TIMEOUT_MILLIS = 15_000f

    /** Kept in step with [MoveOptions] so the editor shows what the NPC API would have used anyway. */
    private val DEFAULT_SPEED = MoveOptions().speed.toFloat()
    private val DEFAULT_DISTANCE = MoveOptions().arrivalDistance.toFloat()
}

private suspend fun NpcEntity.move(position: Vec3, options: MoveOptions) {
    move(pos = position, options = options)
}

private suspend fun NpcEntity.move(target: LivingEntity, options: MoveOptions) {
    move(entity = target, dist = options.arrivalDistance, speed = options.speed)
}
