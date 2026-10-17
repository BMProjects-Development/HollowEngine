package ru.hollowhorizon.hollowengine.client.ui.render

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import org.lwjgl.opengl.*
import ru.hollowhorizon.hollowengine.HollowEngine
import java.nio.FloatBuffer
import java.nio.IntBuffer

/** Fine path rasterization over active tile quads, with a GL 3.3 fragment path and optional GPU compute coarse pass. */
internal class UiPathTileRenderer : UiSdfRenderer(
    name = "path tile",
    vertexShaderPath = VertexShaderPath,
    fragmentShaderPath = FragmentShaderPath,
    samplerNames = arrayOf("SegmentBuffer", "SegmentIndexBuffer", "TileBuffer", "PaintBuffer", "StopBuffer"),
) {
    private val segmentBuffer = UiStreamingTextureBuffer(GL30.GL_RGBA32F)
    private val segmentIndexBuffer = UiStreamingTextureBuffer(GL30.GL_R32I)
    private val tileBuffer = UiStreamingTextureBuffer(GL30.GL_RGBA32I)
    private val paintBuffer = UiStreamingTextureBuffer(GL30.GL_RGBA32F)
    private val stopBuffer = UiStreamingTextureBuffer(GL30.GL_RGBA32F)
    private val computeDispatcher = UiPathTileComputeDispatcher()
    private var vertexData: FloatBuffer? = null
    private var segmentData: FloatBuffer? = null
    private var segmentIndexData: IntBuffer? = null
    private var tileData: IntBuffer? = null
    private var paintData: FloatBuffer? = null
    private var stopData: FloatBuffer? = null

    override val vertexStrideFloats get() = UiPathTileBatch.VertexStride

    fun draw(batch: UiPathTileBatch) {
        if (batch.isEmpty || !isAvailable) return
        val previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        val previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
        val previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)
        val previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        val previousTextureBuffers = captureTextureBufferBindings()
        val preserveComputeState = computeDispatcher.isSupported
        val previousIndirectBuffer = if (preserveComputeState) {
            GL11.glGetInteger(GL40.GL_DRAW_INDIRECT_BUFFER_BINDING)
        } else {
            0
        }
        val previousStorageBuffers = if (preserveComputeState) {
            IntArray(StorageBindingCount) { binding ->
                GL30.glGetIntegeri(GL43.GL_SHADER_STORAGE_BUFFER_BINDING, binding)
            }
        } else {
            null
        }
        try {
            uploadSharedInputs(batch)
            val computed = !batch.prefersBakedCpu && computeDispatcher.dispatch(
                batch,
                vertexBuffer,
                segmentBuffer.storage,
                segmentIndexBuffer.storage,
                tileBuffer.storage,
            )
            if (!computed) {
                batch.prepareCpu()
                uploadCpuOutput(batch)
            }
            withCullStatePreserved {
                RenderSystem.disableCull()
                configureUiBlend()
                bindProgram()
                uploadMatrices()
                bindVertexArray()
                bindTextureBuffers(arrayOf(segmentBuffer, segmentIndexBuffer, tileBuffer, paintBuffer, stopBuffer))
                if (computed) {
                    computeDispatcher.drawIndirect()
                } else {
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, batch.vertexCount)
                }
            }
        } finally {
            previousStorageBuffers?.forEachIndexed { binding, buffer ->
                GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, binding, buffer)
            }
            restoreTextureBufferBindings(previousTextureBuffers, previousActiveTexture)
            GL30.glBindVertexArray(previousVertexArray)
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer)
            if (preserveComputeState) GL15.glBindBuffer(GL40.GL_DRAW_INDIRECT_BUFFER, previousIndirectBuffer)
            GL20.glUseProgram(previousProgram)
        }
    }

    override fun onProgramReady() {
        HollowEngine.LOGGER.info(
            "Initialized UI tiled path renderer ({} coarse pass)",
            if (computeDispatcher.isSupported) "GPU compute" else "CPU",
        )
    }

    override fun onClose() {
        segmentBuffer.close()
        segmentIndexBuffer.close()
        tileBuffer.close()
        paintBuffer.close()
        stopBuffer.close()
        computeDispatcher.close()
        vertexData = vertexData.releaseUiBuffer()
        segmentData = segmentData.releaseUiBuffer()
        segmentIndexData = segmentIndexData.releaseUiBuffer()
        tileData = tileData.releaseUiBuffer()
        paintData = paintData.releaseUiBuffer()
        stopData = stopData.releaseUiBuffer()
    }

    private fun uploadSharedInputs(batch: UiPathTileBatch) {
        segmentData = segmentData.ensureUiCapacity(batch.segmentFloatCount)
        paintData = paintData.ensureUiCapacity(batch.paintFloatCount)
        stopData = stopData.ensureUiCapacity(batch.stopFloatCount)
        val segments = segmentData.prepareUiBuffer(batch.segmentFloatCount)
        val paints = paintData.prepareUiBuffer(batch.paintFloatCount)
        val stops = stopData.prepareUiBuffer(batch.stopFloatCount)
        batch.writeSegments(segments)
        batch.writePaints(paints)
        batch.writeStops(stops)
        segments.flip()
        paints.flip()
        stops.flip()
        segmentBuffer.upload(segments)
        paintBuffer.upload(paints)
        stopBuffer.upload(stops)
    }

    private fun uploadCpuOutput(batch: UiPathTileBatch) {
        vertexData = vertexData.ensureUiCapacity(batch.vertexFloatCount)
        segmentIndexData = segmentIndexData.ensureUiCapacity(batch.segmentIndexCount)
        tileData = tileData.ensureUiCapacity(batch.tileIntCount)
        val vertices = vertexData.prepareUiBuffer(batch.vertexFloatCount)
        val segmentIndices = segmentIndexData.prepareUiBuffer(batch.segmentIndexCount)
        val tiles = tileData.prepareUiBuffer(batch.tileIntCount)
        batch.writeVertices(vertices)
        batch.writeSegmentIndices(segmentIndices)
        batch.writeTiles(tiles)
        vertices.flip()
        segmentIndices.flip()
        tiles.flip()
        vertexBuffer.upload(vertices)
        segmentIndexBuffer.upload(segmentIndices)
        tileBuffer.upload(tiles)
    }

    private companion object {
        const val StorageBindingCount = 8
        val VertexShaderPath = ResourceLocation.fromNamespaceAndPath(
            HollowEngine.MODID,
            "shaders/ui/path_tile.vsh",
        )
        val FragmentShaderPath = ResourceLocation.fromNamespaceAndPath(
            HollowEngine.MODID,
            "shaders/ui/path_tile.fsh",
        )
    }
}

internal object UiPathTileResources : ResourceManagerReloadListener {
    @Volatile
    var generation: Int = 0
        private set

    override fun onResourceManagerReload(resourceManager: ResourceManager) {
        generation++
    }
}
