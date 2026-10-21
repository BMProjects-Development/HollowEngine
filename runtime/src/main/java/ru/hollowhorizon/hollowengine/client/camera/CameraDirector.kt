package ru.hollowhorizon.hollowengine.client.camera

import ru.hollowhorizon.hollowengine.api.extensions.ExtensionHandle
import ru.hollowhorizon.hollowengine.api.extensions.ExtensionPoints
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderItemInHandEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderOverlayEvent
import ru.hollowhorizon.hollowengine.common.utils.rl

/**
 * Something that wants to point the camera, such as a cutscene or a dialogue's own camera.
 */
interface CameraPoseSource {
    /** Where the camera should be, or null while this source leaves it alone. */
    val currentPose: CameraPose?

    /** Whether the hand is hidden while this source holds the camera. */
    val hidesHand: Boolean get() = false

    /** Whether screen overlays such as the vignette are hidden while this source holds the camera. */
    val hidesOverlays: Boolean get() = false
}

/**
 * Decides where the camera is. The render bridge reads [currentPose] alone, so every source reaches the
 * screen through the same path.
 */
@ClientOnly
object CameraDirector {
    /**
     * Sources addons contribute. The one with the highest priority that has a pose wins, so a cutscene
     * registers above a dialogue camera.
     */
    val sources = ExtensionPoints.create("hollowengine:camera/pose-sources".rl, CameraPoseSource::class)

    /**
     * A pose held by a tool that needs a frame seen from a given point. It wins over every source while
     * it is set and hides the hand.
     */
    @Volatile
    var heldPose: CameraPose? = null

    val currentPose: CameraPose?
        get() = heldPose ?: activeSource?.currentPose

    fun register(id: String, source: CameraPoseSource): ExtensionHandle = sources.register(id.rl, source)

    private val activeSource: CameraPoseSource?
        get() = sources.extensions.firstOrNull { it.currentPose != null }

    @SubscribeEvent
    fun onRenderOverlays(event: RenderOverlayEvent.Pre) {
        if (activeSource?.hidesOverlays == true) event.isCanceled = true
    }

    @SubscribeEvent
    fun onRenderHand(event: RenderItemInHandEvent) {
        if (heldPose != null || activeSource?.hidesHand == true) event.isCanceled = true
    }
}
