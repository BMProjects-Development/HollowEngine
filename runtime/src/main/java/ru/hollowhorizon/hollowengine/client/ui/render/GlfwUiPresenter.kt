package ru.hollowhorizon.hollowengine.client.ui.render

import com.mojang.blaze3d.pipeline.TextureTarget
import net.minecraft.client.Minecraft
import org.lwjgl.glfw.GLFW.*
import org.lwjgl.opengl.*
import ru.hollowhorizon.hollowengine.client.ui.HollowUiFrame

/**
 * Draws with Minecraft's GL context and presents a shared color texture in the window's context.
 * Only raw GL calls are made there, so Minecraft's GL state cache and VAOs stay with the main context.
 */
internal class GlfwUiPresenter(private val window: Long) : AutoCloseable {
    private val renderer = MinecraftUiRenderer()
    private var target: TextureTarget? = null
    private var capabilities: GLCapabilities? = null
    private var readFramebuffer = 0

    fun render(frame: HollowUiFrame, width: Int, height: Int, logicalWidth: Float, logicalHeight: Float) {
        val colorTexture = withTarget(frame, width, height, logicalWidth, logicalHeight)
        val rendered = fence()
        var presented = 0L
        try {
            inWindowContext {
                waitFor(rendered)
                GL30C.glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, readFramebuffer)
                GL30C.glFramebufferTexture2D(
                    GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, colorTexture, 0,
                )
                GL30C.glBlitFramebuffer(
                    0, 0, width, height, 0, 0, width, height, GL11C.GL_COLOR_BUFFER_BIT, GL11C.GL_NEAREST,
                )
                GL30C.glFramebufferTexture2D(
                    GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0, GL11C.GL_TEXTURE_2D, 0, 0,
                )
                presented = fence()
                glfwSwapBuffers(window)
            }
        } finally {
            GL32C.glDeleteSync(rendered)
            if (presented != 0L) {
                waitFor(presented)
                GL32C.glDeleteSync(presented)
            }
        }
    }

    private fun withTarget(
        frame: HollowUiFrame,
        width: Int,
        height: Int,
        logicalWidth: Float,
        logicalHeight: Float,
    ): Int {
        var texture = 0
        withOwnRenderTarget {
            val buffer = target ?: TextureTarget(width, height, true, Minecraft.ON_OSX).also { target = it }
            if (buffer.width != width || buffer.height != height) buffer.resize(width, height, Minecraft.ON_OSX)
            val depthWrite = GL11C.glGetBoolean(GL11C.GL_DEPTH_WRITEMASK)
            GL11C.glDepthMask(true)
            try {
                buffer.setClearColor(0f, 0f, 0f, 1f)
                buffer.clear(Minecraft.ON_OSX)
            } finally {
                GL11C.glDepthMask(depthWrite)
            }
            renderer.render(
                frame,
                UiRenderTarget(
                    buffer.frameBufferId,
                    0,
                    0,
                    width,
                    height,
                    logicalWidth,
                    logicalHeight,
                    width / logicalWidth
                ),
            )
            texture = buffer.colorTextureId
        }
        return texture
    }

    private inline fun inWindowContext(block: () -> Unit) {
        val previousWindow = glfwGetCurrentContext()
        val previousCapabilities = GL.getCapabilities()
        try {
            glfwMakeContextCurrent(window)
            val existing = capabilities
            if (existing == null) {
                capabilities = GL.createCapabilities()
                glfwSwapInterval(0)
                readFramebuffer = GL30C.glGenFramebuffers()
            } else {
                GL.setCapabilities(existing)
            }
            block()
        } finally {
            glfwMakeContextCurrent(previousWindow)
            GL.setCapabilities(previousCapabilities)
        }
    }

    override fun close() {
        try {
            renderer.close()
        } finally {
            target?.destroyBuffers()
        }
    }

    private fun fence(): Long = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0).also { GL11C.glFlush() }

    private fun waitFor(sync: Long) = GL32C.glWaitSync(sync, 0, GL32C.GL_TIMEOUT_IGNORED)
}
