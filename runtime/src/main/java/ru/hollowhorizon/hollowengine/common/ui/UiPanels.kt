package ru.hollowhorizon.hollowengine.common.ui

import androidx.compose.runtime.Composable
import net.minecraft.nbt.CompoundTag
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import ru.hollowhorizon.hollowengine.common.data.EntityData
import ru.hollowhorizon.hollowengine.common.data.data

/** The composable body of a panel that hangs on something in the world. */
typealias UiPanelContent = @Composable UiPanelScope.() -> Unit

/**
 * What the content of a panel in the world composes against: the entity it hangs on and that entity's data, which
 * everyone looking at it shares; whatever else it keeps is the viewer's own, in its composition.
 */
interface UiPanelScope {
    /** The object or NPC the panel hangs on. */
    val entity: Entity

    /** The data of [entity], as the server syncs it; reading it in the content follows its changes. */
    val data: EntityData get() = entity.data

    /** Whether the viewer is pointing at the panel right now. */
    val isHovered: Boolean

    /** Sends [payload] to the server, where `onPanelEvent` of the node scripts of [entity] gets it. */
    fun send(payload: CompoundTag)
}

/** Builds and sends a payload to the server, e.g. `send { putString("action", "open") }`. */
fun UiPanelScope.send(builder: CompoundTag.() -> Unit) = send(CompoundTag().apply(builder))

/**
 * A panel declared by a `.ui.kts` script, laid out at [width] by [height] pixels of its own and drawn on whatever the
 * rig attachment `hollowengine:rig/ui_panel` naming it hangs on.
 */
class UiPanelDefinition(
    val id: ResourceLocation,
    val width: Int,
    val height: Int,
    val frameRate: Int,
    val rebuildEveryFrame: Boolean,
    val background: Int,
    val keyboard: Boolean,
    val content: UiPanelContent,
)

class UiPanelBuilder internal constructor(private val id: ResourceLocation) {
    var width: Int = 256
    var height: Int = 160

    /** How many times a second the panel is drawn again at most. */
    var frameRate: Int = 30

    /** Recomposes the panel on every frame it is drawn; enable only for content that tracks live game state. */
    var rebuildEveryFrame: Boolean = false

    /** What fills the panel under its content, as `0xAARRGGBB`; the alpha is ignored, panels are opaque. */
    var background: Int = 0xFF101418.toInt()

    /**
     * Whether the panel takes the keyboard while one of its text fields is clicked into, until Escape or the player
     * looks away. Off, as it is unless asked, the panel takes no keys at all, and the game keeps every key.
     */
    var keyboard: Boolean = false

    private var body: UiPanelContent? = null

    /** The size of the panel in its own pixels; its height in the world follows from its width there. */
    fun size(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    fun content(body: UiPanelContent) {
        this.body = body
    }

    internal fun build() = UiPanelDefinition(
        id = id,
        width = width.coerceIn(1, MAX_SIZE),
        height = height.coerceIn(1, MAX_SIZE),
        frameRate = frameRate.coerceIn(1, 240),
        rebuildEveryFrame = rebuildEveryFrame,
        background = background,
        keyboard = keyboard,
        content = body ?: error("UI panel '$id' declares no content { } block"),
    )

    private companion object {
        const val MAX_SIZE = 1024
    }
}
