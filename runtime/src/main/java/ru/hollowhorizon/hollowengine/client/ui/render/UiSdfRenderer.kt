package ru.hollowhorizon.hollowengine.client.ui.render

import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import org.lwjgl.opengl.*
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import ru.hollowhorizon.hollowengine.HollowEngine

/** Compiles a UI shader stage from a mod resource, throwing with the shader log on failure. */
internal fun compileUiShader(type: Int, location: ResourceLocation, label: String): Int {
    val source = Minecraft.getInstance().resourceManager.getResource(location).orElseThrow {
        IllegalStateException("Missing UI $label shader resource: $location")
    }.open().bufferedReader(Charsets.UTF_8).use { it.readText() }
    val shader = GL20.glCreateShader(type)
    // LWJGL's CharSequence overload is not null-terminated; some AMD drivers read past its explicit length.
    val encodedSource = MemoryUtil.memUTF8(source, true)
    try {
        MemoryStack.stackPush().use { stack ->
            val sources = stack.mallocPointer(1)
            sources.put(0, MemoryUtil.memAddress(encodedSource))
            GL20.glShaderSource(shader, sources, stack.ints(encodedSource.remaining() - 1))
        }
    } finally {
        MemoryUtil.memFree(encodedSource)
    }
    GL20.glCompileShader(shader)
    if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE) return shader
    val log = GL20.glGetShaderInfoLog(shader)
    GL20.glDeleteShader(shader)
    error("Failed to compile UI $label shader $location: $log")
}

/**
 * Shared scaffolding for the buffer-texture backed SDF renderers ([UiAnalyticRectRenderer],
 * [UiPathTileRenderer]): the vertex/fragment program lifecycle, its reload-aware recreation, the
 * `pos3/uv2/tile1` vertex array, matrix upload, sampler binding and texture-buffer state capture.
 * Subclasses own their own storage buffers/native scratch and implement the actual draw.
 */
internal abstract class UiSdfRenderer(
    private val name: String,
    private val vertexShaderPath: ResourceLocation,
    private val fragmentShaderPath: ResourceLocation,
    private val samplerNames: Array<String>,
) : AutoCloseable {
    protected val vertexBuffer = UiStreamingGpuBuffer(GL15.GL_ARRAY_BUFFER)
    protected var program = Uninitialized
        private set
    private var vertexArray = 0
    protected var modelViewLocation = -1
        private set
    protected var projectionLocation = -1
        private set
    protected val samplerLocations = IntArray(samplerNames.size) { -1 }
    private var resourceGeneration = -1

    protected val textureUnitCount get() = samplerNames.size

    /** Float stride of one vertex in [vertexBuffer]; the shared attribute layout is derived from it. */
    protected abstract val vertexStrideFloats: Int

    val isAvailable: Boolean
        get() {
            refreshResources()
            return program != Failed && GL.getCapabilities().OpenGL33 && ensureProgram()
        }

    protected fun ensureProgram(): Boolean {
        if (program > 0) return true
        return runCatching {
            var vertexShader = 0
            var fragmentShader = 0
            try {
                vertexShader = compileUiShader(GL20.GL_VERTEX_SHADER, vertexShaderPath, name)
                fragmentShader = compileUiShader(GL20.GL_FRAGMENT_SHADER, fragmentShaderPath, name)
                program = GL20.glCreateProgram()
                GL20.glAttachShader(program, vertexShader)
                GL20.glAttachShader(program, fragmentShader)
                GL20.glLinkProgram(program)
                check(GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_TRUE) {
                    "Failed to link UI $name program: ${GL20.glGetProgramInfoLog(program)}"
                }
            } finally {
                if (vertexShader != 0) GL20.glDeleteShader(vertexShader)
                if (fragmentShader != 0) GL20.glDeleteShader(fragmentShader)
            }
            modelViewLocation = GL20.glGetUniformLocation(program, "ModelViewMat")
            projectionLocation = GL20.glGetUniformLocation(program, "ProjMat")
            samplerNames.forEachIndexed { unit, sampler ->
                samplerLocations[unit] = GL20.glGetUniformLocation(program, sampler)
            }
            createVertexArray()
            onProgramReady()
            true
        }.getOrElse { error ->
            releasePipeline()
            program = Failed
            HollowEngine.LOGGER.error("Failed to initialize UI $name renderer", error)
            false
        }
    }

    /** Called after the program links; override to log or fetch renderer-specific uniforms. */
    protected open fun onProgramReady() {
        HollowEngine.LOGGER.info("Initialized UI {} renderer", name)
    }

    private fun refreshResources() {
        val currentGeneration = UiPathTileResources.generation
        if (resourceGeneration == currentGeneration) return
        releasePipeline()
        resourceGeneration = currentGeneration
    }

    private fun releasePipeline() {
        if (program > 0) GL20.glDeleteProgram(program)
        program = Uninitialized
        if (vertexArray != 0) GL30.glDeleteVertexArrays(vertexArray)
        vertexArray = 0
        modelViewLocation = -1
        projectionLocation = -1
        samplerLocations.fill(-1)
    }

    private fun createVertexArray() {
        val previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
        val previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)
        vertexArray = GL30.glGenVertexArrays()
        GL30.glBindVertexArray(vertexArray)
        vertexBuffer.bind()
        setupVertexAttributes()
        GL30.glBindVertexArray(previousVertexArray)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer)
    }

    /**
     * Configures the vertex-array attribute layout for [vertexBuffer] (bound as GL_ARRAY_BUFFER when
     * called). The default is the per-vertex `pos3/uv2/tile1` layout; instanced renderers override it
     * to bind per-instance attributes with a divisor.
     */
    protected open fun setupVertexAttributes() {
        val stride = vertexStrideFloats * Float.SIZE_BYTES
        GL20.glEnableVertexAttribArray(0)
        GL20.glVertexAttribPointer(0, 3, GL11.GL_FLOAT, false, stride, 0L)
        GL20.glEnableVertexAttribArray(1)
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, stride, 3L * Float.SIZE_BYTES)
        GL20.glEnableVertexAttribArray(2)
        GL20.glVertexAttribPointer(2, 1, GL11.GL_FLOAT, false, stride, 5L * Float.SIZE_BYTES)
    }

    protected fun bindProgram() = GL20.glUseProgram(program)

    protected fun bindVertexArray() = GL30.glBindVertexArray(vertexArray)

    protected fun uploadMatrices() {
        MemoryStack.stackPush().use { stack ->
            val matrix = stack.mallocFloat(16)
            RenderSystem.getModelViewMatrix().get(matrix)
            GL20.glUniformMatrix4fv(modelViewLocation, false, matrix)
            matrix.clear()
            RenderSystem.getProjectionMatrix().get(matrix)
            GL20.glUniformMatrix4fv(projectionLocation, false, matrix)
        }
    }

    protected fun bindTextureBuffers(buffers: Array<UiStreamingTextureBuffer>) {
        buffers.forEachIndexed { unit, buffer ->
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
            buffer.bindTexture()
            GL20.glUniform1i(samplerLocations[unit], unit)
        }
    }

    protected fun captureTextureBufferBindings(): IntArray = IntArray(textureUnitCount) { unit ->
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
        GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER)
    }

    protected fun restoreTextureBufferBindings(bindings: IntArray, activeTexture: Int) {
        bindings.forEachIndexed { unit, texture ->
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit)
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture)
        }
        GL13.glActiveTexture(activeTexture)
    }

    final override fun close() {
        releasePipeline()
        vertexBuffer.close()
        onClose()
    }

    /** Releases subclass-owned GPU/native resources; the base program, VAO and vertex buffer are handled. */
    protected open fun onClose() {}

    companion object {
        const val Uninitialized = 0
        const val Failed = -1
    }
}
