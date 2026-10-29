package ru.hollowhorizon.hollowengine.addons.mcp.client

import com.mojang.blaze3d.platform.NativeImage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import ru.hollowhorizon.hollowengine.client.camera.CameraDirector
import ru.hollowhorizon.hollowengine.client.camera.CameraPose
import ru.hollowhorizon.hollowengine.client.ui.screen.HollowWaitScreen
import ru.hollowhorizon.hollowengine.client.utils.lang
import ru.hollowhorizon.hollowengine.common.coroutines.dispatcher
import ru.hollowhorizon.hollowengine.common.events.ClientOnly
import ru.hollowhorizon.hollowengine.common.events.SubscribeEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderItemInHandEvent
import ru.hollowhorizon.hollowengine.common.events.client.render.RenderTickEvent
import kotlin.time.Duration.Companion.seconds

/**
 * Takes one frame out of the game's own render loop. A frame of the whole screen is read once it is
 * finished; a frame of the world alone is read after the level is drawn and before the GUI is.
 */
@ClientOnly
object FrameCapture {
    private class Request(val worldOnly: Boolean, val waitForSections: Boolean) {
        val image = CompletableDeferred<NativeImage>()
        var frames = 0
        var settledFrames = 0
        val startedAt = System.currentTimeMillis()
    }

    /** The capture being waited for. Touched only on the render thread. */
    private var pending: Request? = null

    /**
     * Captures the next suitable frame; [pose] moves the camera there first and implies [worldOnly].
     * Null when no frame was drawn in time, as happens while the window is minimized.
     */
    suspend fun capture(worldOnly: Boolean, pose: CameraPose?): NativeImage? {
        val minecraft = Minecraft.getInstance()
        return withContext(minecraft.dispatcher) {
            check(pending == null) { "Another capture is in progress" }
            if ((worldOnly || pose != null) && minecraft.level == null) error("No world is open, so there is nothing to capture but the menu")
            val request = Request(worldOnly = worldOnly || pose != null, waitForSections = pose != null)
            val previousScreen = minecraft.screen
            val waitScreen = pose?.let {
                HollowWaitScreen(WAIT_HEADING.lang, WAIT_MESSAGE.lang).also(minecraft::setScreen)
            }
            CameraDirector.heldPose = pose
            pending = request
            try {
                withTimeoutOrNull(CAPTURE_TIMEOUT) { request.image.await() }
            } finally {
                pending = null
                CameraDirector.heldPose = null
                if (waitScreen != null && minecraft.screen === waitScreen) minecraft.setScreen(previousScreen)
            }
        }
    }

    @SubscribeEvent
    fun onLevelRendered(event: RenderTickEvent.LevelRendered) {
        val request = pending?.takeIf { it.worldOnly } ?: return
        request.frames++
        if (request.waitForSections && !sectionsSettled(event.minecraft, request)) return
        complete(request, event.minecraft)
    }

    /** A picture of the world alone leaves out the hand too, which is drawn with the level. */
    @SubscribeEvent
    fun onRenderHand(event: RenderItemInHandEvent) {
        if (pending?.worldOnly == true) event.isCanceled = true
    }

    @SubscribeEvent
    fun onBlit(event: RenderTickEvent.Blit) {
        val request = pending?.takeIf { !it.worldOnly } ?: return
        complete(request, event.minecraft)
    }

    private fun sectionsSettled(minecraft: Minecraft, request: Request): Boolean {
        if (System.currentTimeMillis() - request.startedAt >= SETTLE_LIMIT_MILLIS) return true
        request.settledFrames = if (minecraft.levelRenderer.hasRenderedAllSections()) request.settledFrames + 1 else 0
        return request.frames >= MIN_SETTLE_FRAMES && request.settledFrames >= 2
    }

    private fun complete(request: Request, minecraft: Minecraft) {
        pending = null
        request.image.complete(Screenshot.takeScreenshot(minecraft.mainRenderTarget))
    }

    private const val WAIT_HEADING = "hollowengine_mcp.wait.heading"
    private const val WAIT_MESSAGE = "hollowengine_mcp.wait.message"
    private const val MIN_SETTLE_FRAMES = 8
    private const val SETTLE_LIMIT_MILLIS = 5_000L
    private val CAPTURE_TIMEOUT = 10.seconds
}
