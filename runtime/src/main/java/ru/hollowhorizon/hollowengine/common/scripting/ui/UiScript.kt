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
 * What every host that can be an overlay has: where it draws and whether it takes input.
 */
sealed class UiOverlayHostBuilder(id: ResourceLocation) : UiHostBuilder(id) {
    private var anchors: List<OverlayAnchor> = listOf(after(VanillaHudLayers.HOTBAR.toString()))

    /**
     * Whether this overlay takes pointer/keyboard input. Draw-only ([OverlayInput.NONE]) is the
     * default; set [OverlayInput.INTERACTIVE] for an overlay the player clicks.
     */
    var input: OverlayInput = OverlayInput.NONE

    /** Shorthand for `input = OverlayInput.INTERACTIVE`. */
    fun interactive() {
        input = OverlayInput.INTERACTIVE
    }

    /**
     * Where the overlay draws, in priority; after the hotbar by default:
     *
     * ```kotlin
     * anchor(after("chat"), over(ChatScreen::class))
     * ```
     *
     * While a screen is open the first [over] that accepts it wins; otherwise the overlay stays in the HUD at its
     * [after] or [before], under the screen. At most one HUD anchor is allowed.
     */
    fun anchor(vararg anchors: OverlayAnchor) {
        require(anchors.isNotEmpty()) { "UI overlay '$id' needs an anchor" }
        this.anchors = anchors.toList()
    }

    /** In the HUD after the vanilla [layer]; see [VanillaHudLayers]. Bare names such as `"crosshair"` are accepted. */
    fun after(layer: String) = OverlayAnchor.HudLayer(VanillaHudLayers.parse(layer), HudPlacement.AFTER)

    /** In the HUD before the vanilla [layer]; see [after]. */
    fun before(layer: String) = OverlayAnchor.HudLayer(VanillaHudLayers.parse(layer), HudPlacement.BEFORE)

    /** On top of any open screen. */
    fun over(level: ScreenLevel = ScreenLevel.CONTENT) = OverlayAnchor.OnScreen(ScreenMatch.AnyScreen, level)

    /** On top of an open [screen], or of one of its subclasses unless [subclasses] is off. */
    fun over(screen: KClass<out Screen>, level: ScreenLevel = ScreenLevel.CONTENT, subclasses: Boolean = true) =
        over(screen.java.name, level, subclasses)

    /** On top of a screen named by its binary class name, for a class this script cannot import. */
    fun over(screen: String, level: ScreenLevel = ScreenLevel.CONTENT, subclasses: Boolean = true) =
        OverlayAnchor.OnScreen(ScreenMatch.OfType(screen, subclasses), level)

    protected fun overlayDefinition(content: UiContent, autoShow: Boolean) = UiOverlayDefinition(
        id = id,
        anchors = anchors,
        autoShow = autoShow,
        input = input,
        rebuildEveryFrame = rebuildEveryFrame,
        exitDuration = exitDuration,
        content = content,
    )
}

/**
 * Declares a surface that switches between an overlay and a screen. It carries both option sets,
 * the overlay's anchor and the screen's title, escape handling and scale, because the same surface
 * really is both at different moments.
 */
class UiSurfaceBuilder internal constructor(id: ResourceLocation) : UiOverlayHostBuilder(id) {
    var title: String = id.path
    var closeOnEscape: Boolean = true
    var pausesGame: Boolean = false
    var guiScale: UiGuiScale = UiGuiScale.Inherit

    private var mode: ((UiData) -> UiSurfaceKind)? = null

    /**
     * Decides which host the surface is right now, from the bound document. Called on the client
     * whenever the document changes.
     */
    fun mode(selector: (UiData) -> UiSurfaceKind) {
        mode = selector
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
            overlay = overlayDefinition(body, autoShow = false),
            mode = mode ?: error("UI surface '$id' declares no mode { } block"),
        )
    }
}

class UiOverlayBuilder internal constructor(id: ResourceLocation) : UiOverlayHostBuilder(id) {
    /**
     * Shows the overlay as soon as the script loads, without waiting for the server. This is the
     * mode for client-owned HUD, a hint about the block under the crosshair has no business
     * round-tripping through the server every frame.
     */
    var autoShow: Boolean = false

    internal fun build(): UiOverlayDefinition = overlayDefinition(requireContent("overlay"), autoShow)
}
