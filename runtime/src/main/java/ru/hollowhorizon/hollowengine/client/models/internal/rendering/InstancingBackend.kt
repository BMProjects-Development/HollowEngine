package ru.hollowhorizon.hollowengine.client.models.internal.rendering

import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import org.lwjgl.opengl.GL33
import ru.hollowhorizon.hollowengine.client.models.internal.INSTANCED_SHADER
import ru.hollowhorizon.hollowengine.client.models.internal.drawWithShader
import ru.hollowhorizon.hollowengine.client.models.internal.manager.HollowModelManager
import ru.hollowhorizon.hollowengine.client.models.internal.opaqueShaderState
import ru.hollowhorizon.hollowengine.client.models.internal.translucentShaderState

interface ModelInstancingBackend {
    fun canBatch(): Boolean = true

    fun flush(batches: Map<StaticBatchKey, List<StaticInstance>>)
}

object VanillaInstancingBackend : ModelInstancingBackend {
    override fun flush(batches: Map<StaticBatchKey, List<StaticInstance>>) {
        if (batches.isEmpty()) return
        withInstancingRenderState {
            for (translucent in listOf(false, true)) {
                val groups = drawOrder(batches, translucent)
                if (groups.isEmpty()) continue
                val shader = INSTANCED_SHADER
                drawWithShader(shader, if (translucent) translucentShaderState() else opaqueShaderState()) {
                    groups.forEach { (key, instances) ->
                        key.part.geometry.drawInstanced(key.part, key.material, shader, instances, InstancedShaderLayoutMode.FIXED)
                    }
                }
            }
        }
    }
}

/**
 * The batches of one pass in the order they are drawn: as submitted when opaque, and far to near, both the batches
 * and the instances within each, when [translucent].
 */
fun drawOrder(
    batches: Map<StaticBatchKey, List<StaticInstance>>,
    translucent: Boolean,
): List<Pair<StaticBatchKey, List<StaticInstance>>> {
    val pass = batches.entries.filter { it.key.material.isTranslucent == translucent }
    if (!translucent) return pass.map { it.key to it.value }
    return pass
        .map { (key, instances) -> key to instances.sortedByDescending(StaticInstance::sortKey) }
        .sortedByDescending { (_, instances) -> instances.first().sortKey }
}

inline fun withInstancingRenderState(body: () -> Unit) {
    val activeTexture = GlStateManager._getActiveTexture()
    val currentVao = GL33.glGetInteger(GL33.GL_VERTEX_ARRAY_BINDING)
    val currentArrayBuffer = GL33.glGetInteger(GL33.GL_ARRAY_BUFFER_BINDING)
    val shaderTexture0 = RenderSystem.getShaderTexture(0)
    val shaderTexture1 = RenderSystem.getShaderTexture(1)
    val shaderTexture2 = RenderSystem.getShaderTexture(2)

    RenderSystem.activeTexture(GL33.GL_TEXTURE2)
    val texture2 = GlStateManager.TEXTURES[GlStateManager.activeTexture].binding
    RenderSystem.bindTexture(HollowModelManager.lightTexture.id)
    RenderSystem.setShaderTexture(2, HollowModelManager.lightTexture.id)

    RenderSystem.activeTexture(GL33.GL_TEXTURE1)
    val texture1 = GlStateManager.TEXTURES[GlStateManager.activeTexture].binding
    Minecraft.getInstance().gameRenderer.overlayTexture().setupOverlayColor()
    RenderSystem.bindTexture(RenderSystem.getShaderTexture(1))
    RenderSystem.setShaderTexture(1, RenderSystem.getShaderTexture(1))
    Minecraft.getInstance().gameRenderer.overlayTexture().teardownOverlayColor()

    RenderSystem.activeTexture(GL33.GL_TEXTURE0)
    val texture0 = GlStateManager.TEXTURES[GlStateManager.activeTexture].binding

    try {
        body()
    } finally {
        RenderSystem.setShaderTexture(0, shaderTexture0)
        RenderSystem.setShaderTexture(1, shaderTexture1)
        RenderSystem.setShaderTexture(2, shaderTexture2)
        RenderSystem.activeTexture(GL33.GL_TEXTURE2)
        RenderSystem.bindTexture(texture2)
        RenderSystem.activeTexture(GL33.GL_TEXTURE1)
        RenderSystem.bindTexture(texture1)
        RenderSystem.activeTexture(GL33.GL_TEXTURE0)
        RenderSystem.bindTexture(texture0)
        RenderSystem.activeTexture(activeTexture)

        RenderSystem.glBindVertexArray(currentVao)
        RenderSystem.glBindBuffer(GL33.GL_ARRAY_BUFFER, currentArrayBuffer)

        GlStateManager._glUseProgram(0)
    }
}
