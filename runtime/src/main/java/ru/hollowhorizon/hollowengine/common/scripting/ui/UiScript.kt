package ru.hollowhorizon.hollowengine.common.scripting.ui

import net.minecraft.client.gui.screens.Screen
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.common.ui.*
import ru.hollowhorizon.hollowengine.common.ui.hud.VanillaHudLayers
import ru.hollowhorizon.hollowengine.common.ui.net.UiSurfaceKind
import kotlin.reflect.KClass

/**
 * Base class of `.ui.kts` scripts. The script body declares screens and HUD overlays; both are
 * addressed by id, which is what the server opens and what other scripts anchor against.
 *
 * ```kotlin
 * val Title = dataKey<String>("title") { "No quest" }
 *
 * screen("mypack:quest_log") {
 *     pausesGame = false
 *     content {
 *         Column(modifier = Modifier.style("mypack:ui/quest.hss")) {
 *             Text(data[Title])
 *             Box(modifier = Modifier.onClick { send { putString("action", "accept") } }) { Text("Accept") }
 *         }
 *     }
 * }
 * ```
 */
abstract class UiScript {
    fun screen(id: String, body: UiScreenBuilder.() -> Unit) {
        val location = ResourceLocation.parse(id)
        UiDefinitionRegistry.register(UiScreenBuilder(location).apply(body).build())
    }

    fun overlay(id: String, body: UiOverlayBuilder.() -> Unit) {
        val location = ResourceLocation.parse(id)
        UiDefinitionRegistry.register(UiOverlayBuilder(location).apply(body).build())
    }

    /**
     * A surface that decides for itself whether it is an overlay or a screen right now:
     *
     * ```kotlin
     * surface("mypack:dialogue") {
     *     anchor = "hotbar"
     *     closeOnEscape = false
     *     mode { data -> if (data[Choices].options.isEmpty()) UiSurfaceKind.OVERLAY else UiSurfaceKind.SCREEN }
     *     content { ... }
     * }
     * ```
     *
     * One `content { }` serves both, and the session and its document survive the switch, so the
     * server writes to one place and never learns which host is currently mounted.
     */
    fun surface(id: String, body: UiSurfaceBuilder.() -> Unit) {
        val location = ResourceLocation.parse(id)
        UiDefinitionRegistry.register(UiSurfaceBuilder(location).apply(body).build())
    }

    /**
     * A panel that hangs on something in the world, as the screen of a computer does, through the rig attachment
     * `hollowengine:rig/ui_panel` that names it. The player points at it with the crosshair and clicks:
     *
     * ```kotlin
     * val Clicks = dataKey<Int>("mypack:clicks", sync = Sync.TRACKING) { 0 }
     *
     * panel("mypack:terminal") {
     *     size(256, 160)
     *     content {
     *         Text("Clicked ${data[Clicks]} times")
     *         Box(modifier = Modifier.onClick { send { putString("action", "click") } }) { Text("Click") }
     *     }
     * }
     * ```
     *
     * The content reads the data of the entity it hangs on, which the server syncs, and sends what the player does
     * to `onPanelEvent` of that entity's node scripts.
     */
    fun panel(id: String, body: UiPanelBuilder.() -> Unit) {
        val location = ResourceLocation.parse(id)
        UiDefinitionRegistry.register(UiPanelBuilder(location).apply(body).build())
    }
}

/**
 * What every declared host has in common. Composable body, whether it recomposes per frame, and
 * the room it asks for its exit animation.
 */
sealed class UiHostBuilder(protected val id: ResourceLocation) {
    /** Recomposes the host every frame; enable only for content that tracks live game state. */
    var rebuildEveryFrame: Boolean = false

    /**
     * How long the host keeps drawing after something asks it to close, in milliseconds.
     */
    var exitDuration: Long = 0L

    private var body: UiContent? = null

    fun content(body: UiContent) {
        this.body = body
    }

    protected fun requireContent(kind: String): UiContent =
        body ?: error("UI $kind '$id' declares no content { } block")
}

class UiScreenBuilder internal constructor(id: ResourceLocation) : UiHostBuilder(id) {
    /** Narration title; also what the game shows for the screen. */
    var title: String = id.path

    /** Whether Escape closes the screen. Turn off for screens the server must dismiss itself. */
    var closeOnEscape: Boolean = true

    /** Whether the screen pauses a singleplayer world while open. */
    var pausesGame: Boolean = false

    /**
     * The GUI scale this screen lays itself out at, independent of the player's video setting:
     * `guiScale = UiGuiScale.Auto` or `guiScale(3)`. A screen built around fixed pixel sizes wants
     * this, at scale 4 a window meant to take half the screen takes all of it.
     */
    var guiScale: UiGuiScale = UiGuiScale.Inherit

    /** Shorthand for `guiScale = UiGuiScale.of(factor)`; `0` means auto. */
    fun guiScale(factor: Int) {
        guiScale = UiGuiScale.of(factor)
    }

    private val overrides = mutableListOf<ScreenOverride>()

    /**
     * This screen replaces [target]: whenever the game displays this screen, the player sees
     * this one instead. The server is not involved in this, so the override also applies in the main menu,
     * where the world and session do not yet exist:
     *
     * ```kotlin
     * screen("mypack:main_menu") {
     *     override(TitleScreen::class)
     *     content { ... }
     * }
     * ```
     */
    fun override(target: KClass<out Screen>, subclasses: Boolean = false) {
        override(target.java.name, subclasses)
    }

    /**
     * Redefines the screen by class name if this script cannot import it. A binary
     * name is used, so a nested class is written as `com.example.Outer$Inner`.
     */
    fun override(target: String, subclasses: Boolean = false) {
        overrides += ScreenOverride(target, subclasses)
    }

    internal fun build(): UiScreenDefinition = UiScreenDefinition(
        id = id,
        title = title,
        closeOnEscape = closeOnEscape,
        pausesGame = pausesGame,
        rebuildEveryFrame = rebuildEveryFrame,
        guiScale = guiScale,
        exitDuration = exitDuration,
        content = requireContent("screen"),
        overrides = overrides.toList(),
    )
}

/**
 * Declares a surface that switches between an overlay and a screen. It carries both option sets,
 * the overlay's anchor and the screen's title, escape handling and scale, because the same surface
 * really is both at different moments.
 */
class UiSurfaceBuilder internal constructor(id: ResourceLocation) : UiHostBuilder(id) {
    var title: String = id.path
    var closeOnEscape: Boolean = true
    var pausesGame: Boolean = false
    var guiScale: UiGuiScale = UiGuiScale.Inherit

    var anchor: String = VanillaHudLayers.HOTBAR.toString()
    var placement: HudPlacement = HudPlacement.AFTER
    var aboveScreens: Boolean = false
    var input: OverlayInput = OverlayInput.NONE

    private var mode: ((UiData) -> UiSurfaceKind)? = null

    /**
     * Decides which host the surface is right now, from the bound document. Called on the client
     * whenever the document changes.
     */
    fun mode(selector: (UiData) -> UiSurfaceKind) {
        mode = selector
    }

    /** Shorthand for `input = OverlayInput.INTERACTIVE`; only matters while it is an overlay. */
    fun interactive() {
        input = OverlayInput.INTERACTIVE
    }

    internal fun build(): UiSurfaceDefinition {
        val body = requireContent("surface")
        return UiSurfaceDefinition(
            id = id,
            screen = UiScreenDefinition(
                id = id,
                title = title,
                closeOnEscape = closeOnEscape,
                pausesGame = pausesGame,
                rebuildEveryFrame = rebuildEveryFrame,
                guiScale = guiScale,
                exitDuration = exitDuration,
                content = body,
            ),
            overlay = UiOverlayDefinition(
                id = id,
                anchor = VanillaHudLayers.parse(anchor),
                placement = placement,
                autoShow = false,
                input = input,
                aboveScreens = aboveScreens,
                rebuildEveryFrame = rebuildEveryFrame,
                exitDuration = exitDuration,
                content = body,
            ),
            mode = mode ?: error("UI surface '$id' declares no mode { } block"),
        )
    }
}

class UiOverlayBuilder internal constructor(id: ResourceLocation) : UiHostBuilder(id) {
    /**
     * The HUD layer this overlay draws next to; see [VanillaHudLayers]. Accepts bare vanilla names
     * (`"crosshair"`) as well as fully qualified ids.
     */
    var anchor: String = VanillaHudLayers.HOTBAR.toString()

    /** Whether this overlay draws before or after its [anchor]. */
    var placement: HudPlacement = HudPlacement.AFTER

    /**
     * Shows the overlay as soon as the script loads, without waiting for the server. This is the
     * mode for client-owned HUD, a hint about the block under the crosshair has no business
     * round-tripping through the server every frame.
     */
    var autoShow: Boolean = false

    /**
     * Whether this overlay takes pointer/keyboard input. Draw-only ([OverlayInput.NONE]) is the
     * default; set [OverlayInput.INTERACTIVE] for an overlay the player clicks. The engine does not
     * force a round trip either way — an interactive overlay can still handle everything client-side.
     */
    var input: OverlayInput = OverlayInput.NONE

    /**
     * Whether the overlay also draws (and takes input, if interactive) on top of an open screen,
     * rather than only over the in-game HUD. Use it for things that must stay visible above a menu.
     */
    var aboveScreens: Boolean = false

    /** Shorthand for `input = OverlayInput.INTERACTIVE`. */
    fun interactive() {
        input = OverlayInput.INTERACTIVE
    }

    internal fun build(): UiOverlayDefinition = UiOverlayDefinition(
        id = id,
        anchor = VanillaHudLayers.parse(anchor),
        placement = placement,
        autoShow = autoShow,
        input = input,
        aboveScreens = aboveScreens,
        rebuildEveryFrame = rebuildEveryFrame,
        exitDuration = exitDuration,
        content = requireContent("overlay"),
    )
}
