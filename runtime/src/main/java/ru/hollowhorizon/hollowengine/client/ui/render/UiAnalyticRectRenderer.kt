package ru.hollowhorizon.hollowengine.client.ui.render

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.resources.ResourceLocation
import org.lwjgl.opengl.*
import ru.hollowhorizon.hollowengine.HollowEngine
import ru.hollowhorizon.hollowengine.client.ui.text.GlyphEdgeSoftness
import java.nio.FloatBuffer

internal class UiAnalyticRectRenderer : UiSdfRenderer(
    name = "analytic rect",
    vertexShaderPath = VertexShaderPath,
    fragmentShaderPath = FragmentShaderPath,
    samplerNames = arrayOf("RecordBuffer", "PaintBuffer", "StopBuffer"),
) {
    private val recordBuffer = UiStreamingTextureBuffer(GL30.GL_RGBA32F)
    private val paintBuffer = UiStreamingTextureBuffer(GL30.GL_RGBA32F)
    private val stopBuffer = UiStreamingTextureBuffer(GL30.GL_RGBA32F)
    private var instanceData: FloatBuffer? = null
    private var recordData: FloatBuffer? = null
    private var paintData: FloatBuffer? = null
    private var stopData: FloatBuffer? = null

    private val fontAtlasLocations = IntArray(UiAnalyticRectBatch.MaxGlyphPages) { -1 }
    private var glyphDistanceRangeLocation = -1
    private var glyphAtlasSizeLocation = -1
    private var glyphEdgeSoftnessLocation = -1

    override val vertexStrideFloats get() = UiAnalyticRectBatch.InstanceStride

    override fun onProgramReady() {
        HollowEngine.LOGGER.info("Initialized UI analytic rectangle renderer (GL 3.3 instanced, unified glyphs)")
        for (page in fontAtlasLocations.indices) {
            fontAtlasLocations[page] = GL20.glGetUniformLocation(program, "FontAtlas$page")
        }
        glyphDistanceRangeLocation = GL20.glGetUniformLocation(program, "GlyphDistanceRange")
        glyphAtlasSizeLocation = GL20.glGetUniformLocation(program, "GlyphAtlasSize")
        glyphEdgeSoftnessLocation = GL20.glGetUniformLocation(program, "GlyphEdgeSoftness")
        assignConstantUniforms()
    }

    private fun assignConstantUniforms() {
        val missing = fontAtlasLocations.indexOfFirst { it < 0 }
        if (missing >= 0) {
            HollowEngine.LOGGER.error(
                "rect_sdf.fsh declares fewer glyph atlases than UiAnalyticRectBatch.MaxGlyphPages " + "({}): no uniform FontAtlas{}",
                UiAnalyticRectBatch.MaxGlyphPages, missing,
            )
        }
        val previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        GL20.glUseProgram(program)
        for (page in fontAtlasLocations.indices) {
            GL20.glUniform1i(fontAtlasLocations[page], GlyphAtlasUnit + page)
        }
        GL20.glUniform1f(glyphEdgeSoftnessLocation, GlyphEdgeSoftness)
        GL20.glUseProgram(previousProgram)
    }

    override fun setupVertexAttributes() {
        val stride = UiAnalyticRectBatch.InstanceStride * Float.SIZE_BYTES
        for (location in 0..4) {
            GL20.glEnableVertexAttribArray(location)
            GL20.glVertexAttribPointer(
                location, 4, GL11.GL_FLOAT, false, stride, location.toLong() * 4L * Float.SIZE_BYTES,
            )
            GL33.glVertexAttribDivisor(location, 1)
        }
    }

    fun draw(batch: UiAnalyticRectBatch) {
        if (batch.isEmpty || !isAvailable) return
        val previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        val previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
        val previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)
        val previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE)
        val previousTextureBuffers = captureTextureBufferBindings()
        try {
            upload(batch)
            withCullStatePreserved {
                RenderSystem.disableCull()
                configureUiBlend()
                bindProgram()
                uploadMatrices()
                bindVertexArray()
                bindTextureBuffers(arrayOf(recordBuffer, paintBuffer, stopBuffer))
                bindGlyphAtlases(batch)
                GL31.glDrawArraysInstanced(GL11.GL_TRIANGLES, 0, 6, batch.instanceCount)
            }
        } finally {
            for (page in 0 until batch.glyphPageCount) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + GlyphAtlasUnit + page)
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0)
            }
            restoreTextureBufferBindings(previousTextureBuffers, previousActiveTexture)
            GL30.glBindVertexArray(previousVertexArray)
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer)
            GL20.glUseProgram(previousProgram)
        }
    }

    private fun bindGlyphAtlases(batch: UiAnalyticRectBatch) {
        if (batch.glyphPageCount == 0) return
        for (page in 0 until batch.glyphPageCount) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + GlyphAtlasUnit + page)
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, batch.glyphPageTexture(page))
        }
        GL20.glUniform1fv(glyphDistanceRangeLocation, batch.glyphPageDistanceRanges())
        GL20.glUniform2fv(glyphAtlasSizeLocation, batch.glyphPageSizes())
    }

    override fun onClose() {
        recordBuffer.close()
        paintBuffer.close()
        stopBuffer.close()
        instanceData = instanceData.releaseUiBuffer()
        recordData = recordData.releaseUiBuffer()
        paintData = paintData.releaseUiBuffer()
        stopData = stopData.releaseUiBuffer()
    }

    private fun upload(batch: UiAnalyticRectBatch) {
        instanceData = instanceData.ensureUiCapacity(batch.instanceFloatCount)
        recordData = recordData.ensureUiCapacity(batch.recordFloatCount)
        paintData = paintData.ensureUiCapacity(batch.paintFloatCount)
        stopData = stopData.ensureUiCapacity(batch.stopFloatCount)
        val instances = instanceData.prepareUiBuffer(batch.instanceFloatCount)
        val records = recordData.prepareUiBuffer(batch.recordFloatCount)
        val paints = paintData.prepareUiBuffer(batch.paintFloatCount)
        val stops = stopData.prepareUiBuffer(batch.stopFloatCount)
        batch.writeInstances(instances)
        batch.writeRecords(records)
        batch.writePaints(paints)
        batch.writeStops(stops)
        instances.flip()
        records.flip()
        paints.flip()
        stops.flip()
        vertexBuffer.upload(instances)
        recordBuffer.upload(records)
        paintBuffer.upload(paints)
        stopBuffer.upload(stops)
    }

    private companion object {
        /** First texture unit for glyph atlases, past the RecordBuffer/PaintBuffer/StopBuffer units (0-2). */
        const val GlyphAtlasUnit = 3

        val VertexShaderPath = ResourceLocation.fromNamespaceAndPath(
            HollowEngine.MODID,
            "shaders/ui/rect_sdf.vsh",
        )
        val FragmentShaderPath = ResourceLocation.fromNamespaceAndPath(
            HollowEngine.MODID,
            "shaders/ui/rect_sdf.fsh",
        )
    }
}
