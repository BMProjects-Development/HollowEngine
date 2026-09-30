package ru.hollowhorizon.hollowengine.client.models.internal.rendering

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import org.lwjgl.opengl.GL33
import ru.hollowhorizon.hollowengine.client.models.internal.drawWithShader
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager

typealias Renderable = RenderContext.() -> Unit

interface RenderPipeline {
    fun addBatchedRenderable(action: Renderable)
    fun addInstancedRenderable(action: Renderable)
    fun addVAORenderable(action: Renderable)
    fun addSkinnable(action: () -> Unit)


    fun render(context: RenderContext) {}

    fun clear()
}


data class RenderContext(
    val stack: PoseStack,
    val source: MultiBufferSource,
    val light: Int,
    val overlay: Int,
    val allowInstancing: Boolean = false,
    val openedBatchedRenderTypes: MutableSet<RenderType>? = null,
)


class ListRenderPipeline : RenderPipeline {
    private val batchedCommands = ArrayList<Renderable>()
    private val instancedCommands = ArrayList<Renderable>()
    private val vaoCommands = ArrayList<Renderable>()
    private val skinCommands = ArrayList<() -> Unit>()

    override fun addBatchedRenderable(action: Renderable) {
        batchedCommands.add(action)
    }

    override fun addInstancedRenderable(action: Renderable) {
        instancedCommands.add(action)
    }

    override fun addVAORenderable(action: Renderable) {
        vaoCommands.add(action)
    }

    override fun addSkinnable(action: () -> Unit) {
        skinCommands.add(action)
    }

    override fun render(context: RenderContext) {
        for (action in batchedCommands) action(context)
        if (instancedCommands.isNotEmpty()) renderInstanced(context)
        if (vaoCommands.isNotEmpty()) renderVAO(context)
    }

    override fun clear() {
        batchedCommands.clear()
        instancedCommands.clear()
        vaoCommands.clear()
        skinCommands.clear()
    }

    fun renderVAO(context: RenderContext) {
        val activeTexture = GlStateManager._getActiveTexture()

        // Binding the saved VAO also restores its element buffer.
        val currentVAO = GL33.glGetInteger(GL33.GL_VERTEX_ARRAY_BINDING)
        val currentArrayBuffer = GL33.glGetInteger(GL33.GL_ARRAY_BUFFER_BINDING)

        transformSkinning()

        GL33.glVertexAttribI2i(3, context.overlay and FFFF, context.overlay shr 16 and FFFF) // Оверлей при ударе
        GL33.glVertexAttribI2i(4, context.light and FFFF, context.light shr 16 and FFFF) // Освещение

        RenderSystem.activeTexture(GL33.GL_TEXTURE2)
        val texture2 = GlStateManager.TEXTURES[GlStateManager.activeTexture].binding
        RenderSystem.bindTexture(HollowModelManager.lightTexture.id)
        RenderSystem.activeTexture(GL33.GL_TEXTURE1)
        val texture1 = GlStateManager.TEXTURES[GlStateManager.activeTexture].binding
        Minecraft.getInstance().gameRenderer.overlayTexture().setupOverlayColor()
        RenderSystem.bindTexture(RenderSystem.getShaderTexture(1))
        Minecraft.getInstance().gameRenderer.overlayTexture().teardownOverlayColor()
        RenderSystem.activeTexture(GL33.GL_TEXTURE0)

        val texture = GlStateManager.TEXTURES[GlStateManager.activeTexture].binding

        drawWithShader {
            for (draw in vaoCommands) context.draw()
        }

        RenderSystem.activeTexture(GL33.GL_TEXTURE2)
        RenderSystem.bindTexture(texture2)

        RenderSystem.activeTexture(GL33.GL_TEXTURE1)
        RenderSystem.bindTexture(texture1)

        RenderSystem.activeTexture(GL33.GL_TEXTURE0)
        RenderSystem.bindTexture(texture)

        RenderSystem.activeTexture(activeTexture)

        RenderSystem.glBindVertexArray(currentVAO)
        RenderSystem.glBindBuffer(GL33.GL_ARRAY_BUFFER, currentArrayBuffer)

        GlStateManager._glUseProgram(0)
    }

    fun renderInstanced(context: RenderContext) {
        val activeTexture = GlStateManager._getActiveTexture()
        val currentVAO = GL33.glGetInteger(GL33.GL_VERTEX_ARRAY_BINDING)
        val currentArrayBuffer = GL33.glGetInteger(GL33.GL_ARRAY_BUFFER_BINDING)

        GL33.glVertexAttribI2i(3, context.overlay and FFFF, context.overlay shr 16 and FFFF)
        GL33.glVertexAttribI2i(4, context.light and FFFF, context.light shr 16 and FFFF)

        RenderSystem.activeTexture(GL33.GL_TEXTURE2)
        val texture2 = GlStateManager.TEXTURES[GlStateManager.activeTexture].binding
        RenderSystem.bindTexture(HollowModelManager.lightTexture.id)
        RenderSystem.activeTexture(GL33.GL_TEXTURE1)
        val texture1 = GlStateManager.TEXTURES[GlStateManager.activeTexture].binding
        Minecraft.getInstance().gameRenderer.overlayTexture().setupOverlayColor()
        RenderSystem.bindTexture(RenderSystem.getShaderTexture(1))
        Minecraft.getInstance().gameRenderer.overlayTexture().teardownOverlayColor()
        RenderSystem.activeTexture(GL33.GL_TEXTURE0)

        val texture = GlStateManager.TEXTURES[GlStateManager.activeTexture].binding

        drawWithShader {
            for (draw in instancedCommands) context.draw()
        }

        RenderSystem.activeTexture(GL33.GL_TEXTURE2)
        RenderSystem.bindTexture(texture2)
        RenderSystem.activeTexture(GL33.GL_TEXTURE1)
        RenderSystem.bindTexture(texture1)
        RenderSystem.activeTexture(GL33.GL_TEXTURE0)
        RenderSystem.bindTexture(texture)
        RenderSystem.activeTexture(activeTexture)

        RenderSystem.glBindVertexArray(currentVAO)
        RenderSystem.glBindBuffer(GL33.GL_ARRAY_BUFFER, currentArrayBuffer)

        GlStateManager._glUseProgram(0)
    }

    private fun transformSkinning() {
        if (skinCommands.isNotEmpty()) {
            GlStateManager._glUseProgram(HollowModelManager.glProgramSkinning)
            GL33.glEnable(GL33.GL_RASTERIZER_DISCARD)
            for (skinCommand in skinCommands) skinCommand()
            GL33.glBindBuffer(GL33.GL_TEXTURE_BUFFER, 0)
            GL33.glDisable(GL33.GL_RASTERIZER_DISCARD)
        }
    }


    companion object {
        private const val FFFF = '\uffff'.code
    }
}
