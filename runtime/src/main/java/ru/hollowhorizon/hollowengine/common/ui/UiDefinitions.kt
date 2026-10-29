package ru.hollowhorizon.hollowengine.common.ui

import androidx.compose.runtime.Composable
import net.minecraft.resources.ResourceLocation
import ru.hollowhorizon.hollowengine.common.ui.net.UiSurfaceKind

/** The composable body of a scripted screen or overlay. */
typealias UiContent = @Composable UiScope.() -> Unit

/** Where a custom HUD layer sits relative to the vanilla layer it is anchored to. */
enum class HudPlacement { BEFORE, AFTER }

/** How much input an overlay takes. Draw-only is the common, cheapest case. */
enum class OverlayInput {
    /** The overlay never receives pointer or keyboard input; the mouse belongs to the game. */
    NONE,

    /**
     * The overlay receives pointer and keyboard input. During gameplay the cursor is locked to the
     * screen center, so an interactive overlay reads the center point; anchored [OverlayAnchor.OnScreen]
     * it sits on top of an open screen (e.g. above the chat box) and takes the real cursor.
     */
    INTERACTIVE,
}

/**
 * A standard or modified screen that replaces the script screen declared using `override(...)`.
 */
class ScreenOverride(
    /** The name of the screen class in binary format, for example, `net.minecraft.client.gui.screens.TitleScreen`. */
    val className: String,
    /** Specifies whether subclasses of [className] will also be replaced, rather than just this specific class. */
    val includeSubclasses: Boolean
)

/**
 * A screen declared by a `.ui.kts` script. The server opens it by [id]; the options travel with the
 * declaration rather than the open call, so a script stays the single source of truth for how its
 * own screen behaves.
 */
class UiScreenDefinition(
    val id: ResourceLocation,
    val title: String,
    val closeOnEscape: Boolean,
    val pausesGame: Boolean,
    val rebuildEveryFrame: Boolean,
    /** The scale this screen lays itself out at; see [UiGuiScale]. */
    val guiScale: UiGuiScale,
    /**
     * Milliseconds the screen stays up after it is closed, so an exit animation can play out.
     */
    val exitDuration: Long,
    val content: UiContent,
    /**
     * Screens this one replaces. An override needs no server session: the client swaps the screen the
     * moment the game opens it, which is what lets a script own the main menu.
     */
    val overrides: List<ScreenOverride> = emptyList(),
)

/**
 * A HUD layer declared by a `.ui.kts` script. It draws where its [anchors] put it: into the vanilla HUD
 * next to a layer, which is how a scripted layer can land under the crosshair but over the hotbar
 * without the engine owning a fixed list of slots, or on top of the screens it names.
 *
 * [autoShow] layers appear as soon as the script loads, the right default for client-side HUD like
 * a "look at a block to see its name" hint, which has no reason to involve the server. [input] opts
 * into pointer/keyboard handling; [rebuildEveryFrame] recomposes the overlay every frame for content
 * that tracks live game state.
 */
class UiOverlayDefinition(
    val id: ResourceLocation,
    /** In priority; see [OverlayAnchor]. At most one of them is in the HUD. */
    val anchors: List<OverlayAnchor>,
    val autoShow: Boolean,
    val input: OverlayInput,
    val rebuildEveryFrame: Boolean,
    val exitDuration: Long,
    val content: UiContent,
) {
    init {
        require(anchors.isNotEmpty()) { "UI overlay '$id' has no anchor" }
        require(anchors.count { it is OverlayAnchor.HudLayer } <= 1) { "UI overlay '$id' has more than one HUD anchor" }
    }

    val isInteractive: Boolean get() = input == OverlayInput.INTERACTIVE

    private val hudLayer = anchors.firstNotNullOfOrNull { it as? OverlayAnchor.HudLayer }

    /** Where the overlay draws while a screen of [screen] is open, or with no screen if it is null; null if nowhere. */
    fun anchorFor(screen: Class<*>?): OverlayAnchor? {
        if (screen == null) return hudLayer
        return anchors.firstOrNull { it is OverlayAnchor.OnScreen && it.screens.matches(screen) } ?: hudLayer
    }
}

/**
 * A surface that is a screen at some moments and a HUD overlay at others, declared by `surface { }`.
 *
 * A dialogue is the case it exists for: the line is an overlay the player reads while the world goes
 * on, and the moment a menu appears it has to become a screen that owns the cursor.
 */
class UiSurfaceDefinition(
    val id: ResourceLocation,
    /** How it behaves while it is a screen. */
    val screen: UiScreenDefinition,
    /** How it behaves while it is an overlay. */
    val overlay: UiOverlayDefinition,
    /** Reads the bound document and says which of the two the surface should be right now. */
    val mode: (UiData) -> UiSurfaceKind,
)