package ru.hollowhorizon.hollowengine.addons.narrative.dialogue

import kotlinx.coroutines.delay
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.any
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.list
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.number
import ru.hollowhorizon.hollowengine.addons.narrative.dialogue.lang.string
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.entity.LivingEntityDeathEvent
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.playCutscene
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.stopCutscene
import ru.hollowhorizon.hollowengine.common.ui.hud.EngineHudLayers
import ru.hollowhorizon.hollowengine.common.ui.hud.VanillaHudLayers
import ru.hollowhorizon.hollowengine.common.ui.net.hiddenHudLayers
import ru.hollowhorizon.hollowengine.common.ui.net.hideHudLayers
import ru.hollowhorizon.hollowengine.common.ui.net.showAllHudLayers
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Screen effects a story can ask for: fades, the HUD, the camera and cutscenes.
 *
 * All of it is client state that a dialogue borrows, so all of it is tracked per session and given
 * back when the dialogue stops, for any reason, including a crash or the server going down mid-scene.
 * A player must never be left staring at a black screen with no HUD and a camera that is not theirs.
 */
internal object StoryEffectFunctions {
    private val OWNED = listOf("fade-in", "fade-out", "hide-hud", "show-hud", "camera", "release-camera", "cutscene")

    /** What each running dialogue has taken over, so it can be handed back. */
    private val borrowed = Collections.synchronizedMap(WeakHashMap<DialogueSession, BorrowedVisuals>())

    private class BorrowedVisuals {
        var fade = false
        var hud = false
        var camera = false
        var cutscene = false

        /** What the player had hidden before the dialogue touched the HUD. */
        val hudBefore = HashMap<ServerPlayer, Set<ResourceLocation>>()
    }

    fun install(registry: StoryFunctionRegistry) {
        OWNED.forEach(registry::unregister)
        registry.fades()
        registry.hud()
        registry.camera()
        registry.cutscenes()
    }

    private fun StoryFunctionRegistry.fades() {
        add(
            "fade-in",
            number("time", default = DEFAULT_FADE_MILLIS.toFloat()),
            string("color", default = "#000000"),
        ) { args ->
            val duration = args.millis("time")
            borrow(session).fade = true
            session.eachPlayer { StoryFadePacket(1f, duration, args.color()).send(it) }
            delay(duration.milliseconds)
        }

        add(
            "fade-out",
            number("time", default = DEFAULT_FADE_MILLIS.toFloat()),
            string("color", default = "#000000"),
        ) { args ->
            val duration = args.millis("time")
            session.eachPlayer { StoryFadePacket(0f, duration, args.color()).send(it) }
            delay(duration.milliseconds)
            borrow(session).fade = false
        }
    }

    /**
     * `@hide-hud` hides everything, `@hide-hud except=chat` keeps what is listed, and
     * `@hide-hud only=[crosshair, hotbar]` hides just those. Both parameters take a list or a
     * comma-separated string, and bare vanilla names (`chat`) as well as full ids.
     */
    private fun StoryFunctionRegistry.hud() {
        add(
            "hide-hud",
            any("only", optional = true, suggestions = LAYER_NAMES),
            any("except", optional = true, suggestions = LAYER_NAMES),
        ) { args ->
            val only = args.layers("only")
            val layers = (only ?: (VanillaHudLayers.all + EngineHudLayers.all)) - args.layers("except").orEmpty()

            val state = borrow(session)
            session.eachPlayer { player ->
                state.hudBefore.putIfAbsent(player, player.hiddenHudLayers())
                player.hideHudLayers(*layers.toTypedArray())
            }
            state.hud = true
        }

        add("show-hud") {
            restoreHud(session)
        }
    }

    /** Accepts `[chat, hotbar]` and `"chat, hotbar"` alike; null when the story left it out. */
    private fun StoryArguments.layers(name: String): Set<ResourceLocation>? {
        val value = this[name] ?: return null
        val names = when (value) {
            is StoryList -> value.values.map { it.display() }
            else -> value.display().split(',')
        }
        return names.map { EngineHudLayers.parse(it.trim()) }.toSet()
    }

    private fun StoryFunctionRegistry.camera() {
        add(
            "camera",
            list("location"), list("rotation"),
            number("time", default = DEFAULT_CAMERA_MILLIS.toFloat()),
            number("fov", default = DEFAULT_FOV),
        ) { args ->
            val position = args.vec3("location")
            val angles = args.list("rotation").map { (it as? StoryNumber)?.value ?: 0f }
            require(angles.size in 2..3) { "camera rotation must be [yaw, pitch] or [yaw, pitch, roll]" }
            val duration = args.millis("time")

            borrow(session).camera = true
            session.eachPlayer {
                StoryCameraPacket(
                    position.x.toFloat(), position.y.toFloat(), position.z.toFloat(),
                    pitch = angles[1], yaw = angles[0], roll = angles.getOrElse(2) { 0f },
                    fov = args.number("fov"),
                    durationMillis = duration,
                ).send(it)
            }
            delay(duration.milliseconds)
        }

        add("release-camera", number("time", default = DEFAULT_CAMERA_MILLIS.toFloat())) { args ->
            val duration = args.millis("time")
            session.eachPlayer { StoryReleaseCameraPacket(duration).send(it) }
            delay(duration.milliseconds)
            borrow(session).camera = false
        }
    }

    private fun StoryFunctionRegistry.cutscenes() {
        add("cutscene", string("name"), number("time", optional = true)) { args ->
            borrow(session).cutscene = true
            session.eachPlayer { it.playCutscene(args.string("name")) }
            args.numberOrNull("time")?.let { delay(it.toLong().milliseconds) }
        }
    }

    /**
     * Every ending goes through here, which is the point: a story that faded out and never faded back
     * in, or that was cut off by a server stop, still gives the screen back.
     */
    @SubscribeEvent
    fun onDialogueEnded(event: DialogueEvent.Ended) = restoreAll(event.session)

    @SubscribeEvent
    fun onPlayerDeath(event: LivingEntityDeathEvent) {
        (event.entity as? ServerPlayer)?.let(::restoreFor)
    }

    /** Hands one player's visuals back without touching the dialogue the others are still in. */
    private fun restoreFor(player: ServerPlayer) {
        val states = synchronized(borrowed) { borrowed.values.toList() }
        for (state in states) {
            if (state.fade) StoryClearFadePacket.send(player)
            if (state.camera || state.cutscene) {
                StoryReleaseCameraPacket(0L).send(player)
                player.stopCutscene()
            }
            if (state.hud && state.hudBefore.containsKey(player)) {
                val before = state.hudBefore.remove(player)
                if (before.isNullOrEmpty()) player.showAllHudLayers() else player.hideHudLayers(*before.toTypedArray())
            }
        }
    }

    private fun restoreAll(session: DialogueSession) {
        val state = borrowed.remove(session) ?: return
        if (state.fade) session.eachPlayer { StoryClearFadePacket.send(it) }
        if (state.camera || state.cutscene) session.eachPlayer {
            StoryReleaseCameraPacket(0L).send(it)
            it.stopCutscene()
        }
        if (state.hud) restoreHudFrom(state, session)
    }

    private fun restoreHud(session: DialogueSession) {
        val state = borrowed[session] ?: return
        restoreHudFrom(state, session)
        state.hud = false
    }

    private fun restoreHudFrom(state: BorrowedVisuals, session: DialogueSession) {
        session.eachPlayer { player ->
            val before = state.hudBefore.remove(player)
            if (before.isNullOrEmpty()) player.showAllHudLayers() else player.hideHudLayers(*before.toTypedArray())
        }
    }

    private fun borrow(session: DialogueSession): BorrowedVisuals =
        borrowed.getOrPut(session) { BorrowedVisuals() }

    private inline fun DialogueSession.eachPlayer(action: (ServerPlayer) -> Unit) =
        onlineParticipants.forEach(action)

    /** `#RRGGBB`, or black when the story did not say. */
    private fun StoryArguments.color(): Int =
        stringOrNull("color")?.removePrefix("#")?.toIntOrNull(16) ?: 0x000000

    /** Offered by the editor for `only=`/`except=`; the bare names, which [VanillaHudLayers.parse] takes. */
    private val LAYER_NAMES = (VanillaHudLayers.all + EngineHudLayers.all)
        .map { if (it.namespace == "minecraft") it.path else it.toString() }
        .sorted()

    private const val DEFAULT_FADE_MILLIS = 500L
    private const val DEFAULT_CAMERA_MILLIS = 1_000L
    private const val DEFAULT_FOV = 70f
}
