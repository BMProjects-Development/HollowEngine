package ru.hollowhorizon.hollowengine.client.ui.render

import net.minecraft.resources.ResourceLocation
import org.lwjgl.opengl.*
import org.lwjgl.system.MemoryUtil
import ru.hollowhorizon.hollowengine.HollowEngine
import java.nio.FloatBuffer

internal class UiPathTileComputeDispatcher : AutoCloseable {
    private val inputBuffer = UiStreamingGpuBuffer(GL43.GL_SHADER_STORAGE_BUFFER, InputBinding)
    private val indirectBuffer = UiStreamingGpuBuffer(GL43.GL_SHADER_STORAGE_BUFFER, IndirectBinding)
    private var inputData: FloatBuffer? = null
    private val indirectData = MemoryUtil.memAllocInt(IndirectIntCount)
    private var program = Uninitialized
    private var candidateCountLocation = -1
    private var candidateOffsetLocation = -1
    private var resourceGeneration = -1

    val isSupported: Boolean
        get() = GL.getCapabilities().OpenGL43

    fun dispatch(
        batch: UiPathTileBatch,
        vertexBuffer: UiStreamingGpuBuffer,
        segmentBuffer: UiStreamingGpuBuffer,
        segmentIndexBuffer: UiStreamingGpuBuffer,
        tileBuffer: UiStreamingGpuBuffer,
    ): Boolean {
        if (!isSupported || !batch.canDispatchCompute || !ensureProgram()) return false
        uploadInputs(batch)
        vertexBuffer.ensureCapacity(
            batch.candidateCount * VerticesPerTile * UiPathTileBatch.VertexStride * Float.SIZE_BYTES,
        )
        segmentIndexBuffer.ensureCapacity(batch.computeSegmentIndexCapacity * Int.SIZE_BYTES)
        tileBuffer.ensureCapacity(batch.candidateCount * UiPathTileBatch.TileStride * Int.SIZE_BYTES)
        resetIndirectBuffer(batch.candidateCount * VerticesPerTile)

        segmentBuffer.bindBase(GL43.GL_SHADER_STORAGE_BUFFER, SegmentBinding)
        segmentIndexBuffer.bindBase(GL43.GL_SHADER_STORAGE_BUFFER, SegmentIndexBinding)
        tileBuffer.bindBase(GL43.GL_SHADER_STORAGE_BUFFER, TileBinding)
        inputBuffer.bindBase()
        vertexBuffer.bindBase(GL43.GL_SHADER_STORAGE_BUFFER, VertexBinding)
        indirectBuffer.bindBase()
        GL20.glUseProgram(program)
        GL42.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT)
        GL20.glUniform1i(candidateCountLocation, batch.candidateCount)
        GL20.glUniform1i(candidateOffsetLocation, batch.pathCount * PathVec4Stride)
        GL43.glDispatchCompute((batch.candidateCount + WorkGroupSize - 1) / WorkGroupSize, 1, 1)
        GL42.glMemoryBarrier(
            GL43.GL_SHADER_STORAGE_BARRIER_BIT or
                    GL42.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT or
                    GL42.GL_TEXTURE_FETCH_BARRIER_BIT or
                    GL43.GL_COMMAND_BARRIER_BIT,
        )
        return true
    }

    fun drawIndirect() {
        indirectBuffer.bindAs(GL40.GL_DRAW_INDIRECT_BUFFER)
        GL40.glDrawArraysIndirect(GL11.GL_TRIANGLES, 0L)
    }

    override fun close() {
        releaseProgram()
        inputBuffer.close()
        indirectBuffer.close()
        inputData = inputData.releaseUiBuffer()
        MemoryUtil.memFree(indirectData)
    }

    private fun uploadInputs(batch: UiPathTileBatch) {
        inputData = inputData.ensureUiCapacity(batch.computeInputFloatCount)
        val inputs = inputData.prepareUiBuffer(batch.computeInputFloatCount)
        batch.writeComputeInputs(inputs)
        inputs.flip()
        inputBuffer.upload(inputs)
    }

    private fun resetIndirectBuffer(vertexCount: Int) {
        indirectData.clear()
        indirectData.put(vertexCount)
        indirectData.put(1)
        indirectData.put(0)
        indirectData.put(0)
        indirectData.put(0)
        indirectData.flip()
        indirectBuffer.upload(indirectData)
    }

    private fun ensureProgram(): Boolean {
        val currentGeneration = UiPathTileResources.generation
        if (resourceGeneration != currentGeneration) {
            releaseProgram()
            resourceGeneration = currentGeneration
        }
        if (program > 0) return true
        if (program == Failed) return false
        return runCatching {
            val shader = compileUiShader(GL43.GL_COMPUTE_SHADER, ComputeShaderPath, "path tile compute")
            try {
                program = GL20.glCreateProgram()
                GL20.glAttachShader(program, shader)
                GL20.glLinkProgram(program)
                check(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_TRUE) {
                    "Failed to link UI path tile compute program: ${GL20.glGetProgramInfoLog(program)}"
                }
            } finally {
                GL20.glDeleteShader(shader)
            }
            candidateCountLocation = GL20.glGetUniformLocation(program, "CandidateCount")
            candidateOffsetLocation = GL20.glGetUniformLocation(program, "CandidateOffset")
            true
        }.getOrElse { error ->
            releaseProgram()
            program = Failed
            HollowEngine.LOGGER.error("Failed to initialize UI path tile compute renderer; using CPU coarse fallback", error)
            false
        }
    }

    private fun releaseProgram() {
        if (program > 0) GL20.glDeleteProgram(program)
        program = Uninitialized
        candidateCountLocation = -1
        candidateOffsetLocation = -1
    }

    companion object {
        private const val Uninitialized = 0
        private const val Failed = -1
        private const val WorkGroupSize = 64
        private const val PathVec4Stride = UiPathTileBatch.PathStride / 4
        private const val VerticesPerTile = 6
        private const val IndirectIntCount = 5
        private const val SegmentBinding = 0
        private const val SegmentIndexBinding = 1
        private const val TileBinding = 2
        private const val InputBinding = 5
        private const val IndirectBinding = 6
        private const val VertexBinding = 7
        private val ComputeShaderPath = ResourceLocation.fromNamespaceAndPath(
            HollowEngine.MODID,
            "shaders/ui/path_tile.csh",
        )
    }
}
