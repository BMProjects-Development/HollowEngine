package ru.hollowhorizon.hollowengine.addons.video.screen

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import org.lwjgl.glfw.GLFW
import ru.hollowhorizon.hollowengine.addons.video.playback.VideoPlayerSession
import ru.hollowhorizon.hollowengine.api.VideoPlaybackOptions
import ru.hollowhorizon.hollowengine.common.utils.literal
import kotlin.math.min
import kotlin.math.roundToInt

class HollowVideoScreen(
    private val session: VideoPlayerSession,
    private val options: VideoPlaybackOptions = VideoPlaybackOptions(),
    private val onClosed: () -> Unit = {},
) : Screen("".literal) {
    override fun render(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        guiGraphics.fill(0, 0, width, height, BackgroundColor)
        drawVideo(guiGraphics)
        if (session.error != null || (options.closeOnEnd && session.ended)) {
            closeScreen()
        }
    }

    override fun renderBackground(guiGraphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) = Unit

    override fun shouldCloseOnEsc(): Boolean = true

    override fun isPauseScreen(): Boolean = false

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
        if (keyCode == GLFW.GLFW_KEY_SPACE) {
            if (session.playing) session.pause() else session.play()
            return true
        }
        return super.keyPressed(keyCode, scanCode, modifiers)
    }

    override fun removed() {
        session.close()
        onClosed()
        super.removed()
    }

    private fun drawVideo(guiGraphics: GuiGraphics) {
        val texture = session.texture ?: return
        val videoWidth = session.videoWidth
        val videoHeight = session.videoHeight
        if (videoWidth <= 0 || videoHeight <= 0) return

        val scale = min(width.toFloat() / videoWidth, height.toFloat() / videoHeight)
        val drawWidth = (videoWidth * scale).roundToInt()
        val drawHeight = (videoHeight * scale).roundToInt()
        val left = (width - drawWidth) / 2
        val top = (height - drawHeight) / 2
        guiGraphics.blit(
            texture,
            left,
            top,
            drawWidth,
            drawHeight,
            0f,
            0f,
            videoWidth,
            videoHeight,
            videoWidth,
            videoHeight,
        )
    }

    private fun closeScreen() {
        Minecraft.getInstance().setScreen(null)
    }
}

private const val BackgroundColor = 0xFF000000.toInt()
