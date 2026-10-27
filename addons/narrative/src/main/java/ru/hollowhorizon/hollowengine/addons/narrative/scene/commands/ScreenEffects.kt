package ru.hollowhorizon.hollowengine.addons.narrative.scene.commands

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.phys.Vec3
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.playCutscene
import ru.hollowhorizon.hollowengine.addons.narrative.cutscene.stopCutscene
import ru.hollowhorizon.hollowengine.addons.narrative.scene.*
import ru.hollowhorizon.hollowengine.addons.narrative.story.*
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.entity.LivingEntityDeathEvent
import ru.hollowhorizon.hollowengine.common.ui.hud.EngineHudLayers
import ru.hollowhorizon.hollowengine.common.ui.hud.VanillaHudLayers
import ru.hollowhorizon.hollowengine.common.ui.net.hiddenHudLayers
import ru.hollowhorizon.hollowengine.common.ui.net.hideHudLayers
import ru.hollowhorizon.hollowengine.common.ui.net.showAllHudLayers
import java.util.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** `@fade-in player 600ms #000000`: covers the screen of [this] with [color]. Undone, the screen is clear again. */
@Action("fade-in")
suspend fun ServerPlayer.fadeIn(time: Duration = DEFAULT_FADE, color: String = BLACK) =
    action(this, time, color) {
        undo { StoryClearFadePacket.send(this@fadeIn) }
        fastForward { StoryFadePacket(1f, 0L, parseColor(color)).send(this@fadeIn) }
        perform {
            ScreenEffects.borrow(this@fadeIn)?.fade = true
            StoryFadePacket(1f, time.inWholeMilliseconds, parseColor(color)).send(this@fadeIn)
            delay(time)
        }
    }

/** Uncovers the screen [fadeIn] covered. Undone, it is covered with [color] again. */
@Action("fade-out")
suspend fun ServerPlayer.fadeOut(time: Duration = DEFAULT_FADE, color: String = BLACK) =
    action(this, time, color) {
        undo { StoryFadePacket(1f, 0L, parseColor(color)).send(this@fadeOut) }
        fastForward { StoryClearFadePacket.send(this@fadeOut) }
        perform {
            StoryFadePacket(0f, time.inWholeMilliseconds, parseColor(color)).send(this@fadeOut)
            delay(time)
        }
    }

/**
 * `@hide-hud player` hides the whole HUD, `except=[chat]` keeps what is listed and `only=[crosshair, hotbar]` hides
 * just those; vanilla layers go by their bare names. Undone, the HUD is as it was before.
 */
@Action("hide-hud")
suspend fun ServerPlayer.hideHud(
    @Suggestions(HudLayerNames::class) only: List<String>? = null,
    @Suggestions(HudLayerNames::class) except: List<String>? = null,
) = action(this, only, except) {
    val before = capture { hiddenHudLayers().map(ResourceLocation::toString) }
    undo { restoreHud(before) }
    perform {
        val layers = (only?.let(::layersOf) ?: ALL_LAYERS) - except?.let(::layersOf).orEmpty()
        ScreenEffects.borrow(this@hideHud)?.hudBefore?.putIfAbsent(uuid, hiddenHudLayers())
        hideHudLayers(*layers.toTypedArray())
    }
}

/** Shows the HUD as it was before the scene hid it, or all of it. Undone, the HUD is as it was before. */
@Action("show-hud")
suspend fun ServerPlayer.showHud() = action(this) {
    val before = capture { hiddenHudLayers().map(ResourceLocation::toString) }
    undo { restoreHud(before) }
    perform { ScreenEffects.giveHudBack(this@showHud, ScreenEffects.borrowed(this@showHud)?.hudBefore?.remove(uuid)) }
}

/**
 * `@camera player [25, 70, -171] [-158, 2] time=1500 fov=60`: moves the camera of [this] to [location] facing
 * [rotation], `[yaw, pitch]` or `[yaw, pitch, roll]` as F3 shows them. Undone, the player has their camera back.
 */
@Action("camera")
suspend fun ServerPlayer.moveCamera(
    location: Vec3,
    rotation: List<Float>,
    time: Duration = DEFAULT_CAMERA,
    fov: Float = DEFAULT_FOV,
) = action(this, location, rotation, time, fov) {
    require(rotation.size in 2..3) { "camera rotation must be [yaw, pitch] or [yaw, pitch, roll]" }
    fun pose(duration: Duration) = StoryCameraPacket(
        location.x.toFloat(), location.y.toFloat(), location.z.toFloat(),
        pitch = rotation[1], yaw = rotation[0], roll = rotation.getOrElse(2) { 0f },
        fov = fov,
        durationMillis = duration.inWholeMilliseconds,
    )
    undo { StoryReleaseCameraPacket(0L).send(this@moveCamera) }
    fastForward { pose(Duration.ZERO).send(this@moveCamera) }
    perform {
        ScreenEffects.borrow(this@moveCamera)?.camera = true
        pose(time).send(this@moveCamera)
        delay(time)
    }
}

/** Gives the player their camera back over [time]. */
@SimpleAction("release-camera")
suspend fun ServerPlayer.releaseCamera(time: Duration = DEFAULT_CAMERA) {
    StoryReleaseCameraPacket(time.inWholeMilliseconds).send(this)
    delay(time)
}

/** Plays the cutscene [name] to [this]; waits [time] when given. */
@SimpleAction("cutscene")
suspend fun ServerPlayer.showCutscene(name: String, time: Duration? = null) {
    ScreenEffects.borrow(this)?.cutscene = true
    playCutscene(name)
    time?.let { delay(it) }
}

/**
 * The screens scenes borrow. A scene that faded the screen out, hid the HUD or took the camera gives them back when it
 * ends for any reason, a crash and a server stop included, and when the player dies: nobody may be left staring at a
 * black screen with no HUD and a camera that is not theirs.
 */
internal object ScreenEffects {
    class Borrowed {
        var fade = false
        var camera = false
        var cutscene = false
        val players = LinkedHashSet<ServerPlayer>()

        /** What each player had hidden before the scene touched the HUD. */
        val hudBefore = HashMap<UUID, Set<ResourceLocation>>()
    }

    private val borrowed = Collections.synchronizedMap(WeakHashMap<SceneSession, Borrowed>())

    /** What the scene the current command runs in borrowed, with [player] noted; null outside a scene. */
    suspend fun borrow(player: ServerPlayer): Borrowed? {
        val session = currentCoroutineContext()[CurrentScene]?.session ?: return null
        return borrowed.getOrPut(session) { Borrowed() }.also { it.players += player }
    }

    suspend fun borrowed(player: ServerPlayer): Borrowed? =
        currentCoroutineContext()[CurrentScene]?.session?.let(borrowed::get)?.takeIf { player in it.players }

    @SubscribeEvent
    fun onDialogueEnded(event: SceneEvent.Ended) {
        val state = borrowed.remove(event.session) ?: return
        state.players.forEach { giveBack(it, state) }
    }

    @SubscribeEvent
    fun onPlayerDeath(event: LivingEntityDeathEvent) {
        val player = event.entity as? ServerPlayer ?: return
        val states = synchronized(borrowed) { borrowed.values.toList() }
        states.filter { player in it.players }.forEach { giveBack(player, it) }
    }

    private fun giveBack(player: ServerPlayer, state: Borrowed) {
        if (state.fade) StoryClearFadePacket.send(player)
        if (state.camera || state.cutscene) {
            StoryReleaseCameraPacket(0L).send(player)
            player.stopCutscene()
        }
        if (state.hudBefore.containsKey(player.uuid)) giveHudBack(player, state.hudBefore.remove(player.uuid))
    }

    fun giveHudBack(player: ServerPlayer, before: Set<ResourceLocation>?) {
        if (before.isNullOrEmpty()) player.showAllHudLayers() else player.hideHudLayers(*before.toTypedArray())
    }
}

private fun ServerPlayer.restoreHud(hidden: List<String>) =
    ScreenEffects.giveHudBack(this, hidden.mapNotNull(ResourceLocation::tryParse).toSet())

/** The HUD layers by the names [hideHud] takes: bare for vanilla ones. */
object HudLayerNames : SuggestionSource {
    override val values: List<String>
        get() = ALL_LAYERS.map { if (it.namespace == "minecraft") it.path else it.toString() }.sorted()
}

/** Accepts bare vanilla names (`chat`) and full ids alike. */
private fun layersOf(names: List<String>): Set<ResourceLocation> =
    names.map { EngineHudLayers.parse(it.trim()) }.toSet()

/** `#RRGGBB`, or black when it is not one. */
private fun parseColor(color: String): Int = color.removePrefix("#").toIntOrNull(16) ?: 0x000000

private val ALL_LAYERS: Set<ResourceLocation> get() = (VanillaHudLayers.all + EngineHudLayers.all).toSet()

private val DEFAULT_FADE = 500.milliseconds
private val DEFAULT_CAMERA = 1_000.milliseconds
private const val DEFAULT_FOV = 70f
private const val BLACK = "#000000"
