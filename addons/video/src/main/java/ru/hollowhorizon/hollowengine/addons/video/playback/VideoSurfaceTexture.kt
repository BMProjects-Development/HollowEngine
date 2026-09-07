package ru.hollowhorizon.hollowengine.addons.video.playback

import com.mojang.blaze3d.platform.GlStateManager
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.packs.resources.ResourceManager
import org.lwjgl.opengl.*
import ru.hollowhorizon.hollowengine.addons.video.decode.VideoPixelFormat
import ru.hollowhorizon.hollowengine.addons.video.decode.YuvVideoFrame
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger

/**
 * GPU side of a playback session: decoded YUV planes are uploaded into R8/RG8 textures and converted
 * to RGB by a fragment shader rendering into an FBO-backed RGBA texture. That output texture is
 * registered with Minecraft's [net.minecraft.client.renderer.texture.TextureManager], so anything that
 * draws by [ResourceLocation] (GUI blits, the Compose `Video` widget, world quads) can display the video.
 */
class VideoSurfaceTexture : AutoCloseable {
    val location: ResourceLocation = ResourceLocation.fromNamespaceAndPath(
        "hollowengine",
        "video/session_${SessionIds.incrementAndGet()}",
    )

    var width = 0
        private set
    var height = 0
        private set

    val ready: Boolean get() = registered

    private var planeTextures = IntArray(0)
    private var outputTexture = 0
    private var framebuffer = 0
    private var quadVao = 0
    private var quadVbo = 0
    private var program = 0
    private var uniforms = ColorUniforms()
    private var format: VideoPixelFormat? = null
    private var registered = false
    private var appliedColorKey = Long.MIN_VALUE

    fun upload(frame: YuvVideoFrame) {
        ensureResources(frame)
        uploadPlanes(frame)
        convert(frame)
        if (!registered) {
            Minecraft.getInstance().textureManager.register(location, VideoOutputTexture(outputTexture))
            registered = true
        }
    }

    override fun close() {
        if (registered) {
            Minecraft.getInstance().textureManager.release(location)
            registered = false
        }
        if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer)
        if (outputTexture != 0) GL11.glDeleteTextures(outputTexture)
        planeTextures.forEach { if (it != 0) GL11.glDeleteTextures(it) }
        if (quadVbo != 0) GL15.glDeleteBuffers(quadVbo)
        if (quadVao != 0) GL30.glDeleteVertexArrays(quadVao)
        if (program != 0) GL20.glDeleteProgram(program)
        framebuffer = 0
        outputTexture = 0
        planeTextures = IntArray(0)
        quadVbo = 0
        quadVao = 0
        program = 0
        width = 0
        height = 0
        format = null
    }

    private fun ensureResources(frame: YuvVideoFrame) {
        if (width == frame.width && height == frame.height && format == frame.format) return
        val previousFormat = format
        width = frame.width
        height = frame.height
        format = frame.format

        if (program == 0 || previousFormat != frame.format) {
            if (program != 0) GL20.glDeleteProgram(program)
            program = compileProgram(frame.format)
            uniforms = ColorUniforms(program)
            appliedColorKey = Long.MIN_VALUE
        }
        if (quadVao == 0) createQuad()

        planeTextures.forEach { if (it != 0) GL11.glDeleteTextures(it) }
        val chromaWidth = frame.chromaWidth
        val chromaHeight = frame.chromaHeight
        planeTextures = when (frame.format) {
            VideoPixelFormat.YUV420P -> intArrayOf(
                createTexture(GL30.GL_R8, width, height),
                createTexture(GL30.GL_R8, chromaWidth, chromaHeight),
                createTexture(GL30.GL_R8, chromaWidth, chromaHeight),
            )

            VideoPixelFormat.NV12 -> intArrayOf(
                createTexture(GL30.GL_R8, width, height),
                createTexture(GL30.GL_RG8, chromaWidth, chromaHeight),
            )
        }

        if (outputTexture != 0) GL11.glDeleteTextures(outputTexture)
        outputTexture = createTexture(GL11.GL_RGBA8, width, height)
        if (framebuffer == 0) framebuffer = GL30.glGenFramebuffers()
        val previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
        GL30.glFramebufferTexture2D(
            GL30.GL_FRAMEBUFFER,
            GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D,
            outputTexture,
            0
        )
        check(GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) == GL30.GL_FRAMEBUFFER_COMPLETE) {
            "Video framebuffer is incomplete"
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer)

        if (registered) {
            Minecraft.getInstance().textureManager.register(location, VideoOutputTexture(outputTexture))
        }
    }

    private fun createTexture(internalFormat: Int, textureWidth: Int, textureHeight: Int): Int {
        val texture = GL11.glGenTextures()
        withTextureBinding(texture) {
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE)
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE)
            val format =
                if (internalFormat == GL30.GL_RG8) GL30.GL_RG else if (internalFormat == GL30.GL_R8) GL11.GL_RED else GL11.GL_RGBA
            GL11.glTexImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                internalFormat,
                textureWidth,
                textureHeight,
                0,
                format,
                GL11.GL_UNSIGNED_BYTE,
                null as ByteBuffer?
            )
        }
        return texture
    }

    private fun uploadPlanes(frame: YuvVideoFrame) {
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1)
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0)
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0)

        val data = frame.data
        when (frame.format) {
            VideoPixelFormat.YUV420P -> {
                uploadPlane(planeTextures[0], frame.width, frame.height, GL11.GL_RED, data, 0, frame.lumaBytes)
                uploadPlane(
                    planeTextures[1],
                    frame.chromaWidth,
                    frame.chromaHeight,
                    GL11.GL_RED,
                    data,
                    frame.lumaBytes,
                    frame.chromaPlaneBytes
                )
                uploadPlane(
                    planeTextures[2],
                    frame.chromaWidth,
                    frame.chromaHeight,
                    GL11.GL_RED,
                    data,
                    frame.lumaBytes + frame.chromaPlaneBytes,
                    frame.chromaPlaneBytes
                )
            }

            VideoPixelFormat.NV12 -> {
                uploadPlane(planeTextures[0], frame.width, frame.height, GL11.GL_RED, data, 0, frame.lumaBytes)
                uploadPlane(
                    planeTextures[1],
                    frame.chromaWidth,
                    frame.chromaHeight,
                    GL30.GL_RG,
                    data,
                    frame.lumaBytes,
                    frame.chromaPlaneBytes * 2
                )
            }
        }
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4)
    }

    private fun uploadPlane(
        texture: Int,
        planeWidth: Int,
        planeHeight: Int,
        dataFormat: Int,
        data: ByteBuffer,
        offset: Int,
        size: Int,
    ) {
        val slice = data.duplicate()
        slice.position(offset).limit(offset + size)
        withTextureBinding(texture) {
            GL11.glTexSubImage2D(
                GL11.GL_TEXTURE_2D,
                0,
                0,
                0,
                planeWidth,
                planeHeight,
                dataFormat,
                GL11.GL_UNSIGNED_BYTE,
                slice
            )
        }
    }

    private fun convert(frame: YuvVideoFrame) {
        val previousFramebuffer = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
        val previousViewport = IntArray(4)
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, previousViewport)
        val previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        val previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
        val blendWasEnabled = GL11.glIsEnabled(GL11.GL_BLEND)
        val scissorWasEnabled = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
        val depthWasEnabled = GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
        val cullWasEnabled = GL11.glIsEnabled(GL11.GL_CULL_FACE)

        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer)
        GL11.glViewport(0, 0, width, height)
        if (blendWasEnabled) GlStateManager._disableBlend()
        if (scissorWasEnabled) GlStateManager._disableScissorTest()
        if (depthWasEnabled) GlStateManager._disableDepthTest()
        if (cullWasEnabled) GlStateManager._disableCull()

        GL20.glUseProgram(program)
        applyColorUniforms(frame)

        val previousUnit = GlStateManager._getActiveTexture()
        val previousBindings = IntArray(planeTextures.size)
        for (index in planeTextures.indices) {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + index)
            previousBindings[index] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
            GlStateManager._bindTexture(planeTextures[index])
        }

        GL30.glBindVertexArray(quadVao)
        GL11.glDrawArrays(GL11.GL_TRIANGLE_STRIP, 0, 4)
        GL30.glBindVertexArray(previousVao)

        for (index in planeTextures.indices) {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0 + index)
            GlStateManager._bindTexture(previousBindings[index])
        }
        GlStateManager._activeTexture(previousUnit)

        GL20.glUseProgram(previousProgram)
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFramebuffer)
        GL11.glViewport(previousViewport[0], previousViewport[1], previousViewport[2], previousViewport[3])
        if (blendWasEnabled) GlStateManager._enableBlend()
        if (scissorWasEnabled) GlStateManager._enableScissorTest()
        if (depthWasEnabled) GlStateManager._enableDepthTest()
        if (cullWasEnabled) GlStateManager._enableCull()
    }

    private fun applyColorUniforms(frame: YuvVideoFrame) {
        val key = (if (frame.fullRange) 1L else 0L) or (if (frame.bt709) 2L else 0L)
        if (key == appliedColorKey) return
        appliedColorKey = key

        val kr = if (frame.bt709) 0.2126f else 0.299f
        val kb = if (frame.bt709) 0.0722f else 0.114f
        val kg = 1f - kr - kb
        GL20.glUniform1f(uniforms.yOffset, if (frame.fullRange) 0f else 16f / 255f)
        GL20.glUniform1f(uniforms.yScale, if (frame.fullRange) 1f else 255f / 219f)
        GL20.glUniform1f(uniforms.cOffset, 128f / 255f)
        GL20.glUniform1f(uniforms.cScale, if (frame.fullRange) 1f else 255f / 224f)
        GL20.glUniform1f(uniforms.rv, 2f * (1f - kr))
        GL20.glUniform1f(uniforms.gu, 2f * kb * (1f - kb) / kg)
        GL20.glUniform1f(uniforms.gv, 2f * kr * (1f - kr) / kg)
        GL20.glUniform1f(uniforms.bu, 2f * (1f - kb))
    }

    private fun createQuad() {
        quadVao = GL30.glGenVertexArrays()
        quadVbo = GL15.glGenBuffers()
        val previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING)
        val previousBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING)
        GL30.glBindVertexArray(quadVao)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadVbo)
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
        )
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STATIC_DRAW)
        GL20.glEnableVertexAttribArray(PositionAttribute)
        GL20.glVertexAttribPointer(PositionAttribute, 2, GL11.GL_FLOAT, false, 16, 0L)
        GL20.glEnableVertexAttribArray(UvAttribute)
        GL20.glVertexAttribPointer(UvAttribute, 2, GL11.GL_FLOAT, false, 16, 8L)
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousBuffer)
        GL30.glBindVertexArray(previousVao)
    }

    private fun compileProgram(format: VideoPixelFormat): Int {
        val vertex = compileShader(GL20.GL_VERTEX_SHADER, VertexShaderSource)
        val fragment = compileShader(
            GL20.GL_FRAGMENT_SHADER,
            when (format) {
                VideoPixelFormat.YUV420P -> Yuv420FragmentSource
                VideoPixelFormat.NV12 -> Nv12FragmentSource
            },
        )
        val created = GL20.glCreateProgram()
        GL20.glAttachShader(created, vertex)
        GL20.glAttachShader(created, fragment)
        GL20.glBindAttribLocation(created, PositionAttribute, "Position")
        GL20.glBindAttribLocation(created, UvAttribute, "UV")
        GL20.glLinkProgram(created)
        GL20.glDeleteShader(vertex)
        GL20.glDeleteShader(fragment)
        check(GL20.glGetProgrami(created, GL20.GL_LINK_STATUS) == GL11.GL_TRUE) {
            "Failed to link video shader: ${GL20.glGetProgramInfoLog(created)}"
        }
        val previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM)
        GL20.glUseProgram(created)
        GL20.glUniform1i(GL20.glGetUniformLocation(created, "SamplerY"), 0)
        when (format) {
            VideoPixelFormat.YUV420P -> {
                GL20.glUniform1i(GL20.glGetUniformLocation(created, "SamplerU"), 1)
                GL20.glUniform1i(GL20.glGetUniformLocation(created, "SamplerV"), 2)
            }

            VideoPixelFormat.NV12 -> GL20.glUniform1i(GL20.glGetUniformLocation(created, "SamplerUV"), 1)
        }
        GL20.glUseProgram(previousProgram)
        return created
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GL20.glCreateShader(type)
        GL20.glShaderSource(shader, source)
        GL20.glCompileShader(shader)
        check(GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_TRUE) {
            "Failed to compile video shader: ${GL20.glGetShaderInfoLog(shader)}"
        }
        return shader
    }

    private inline fun withTextureBinding(texture: Int, body: () -> Unit) {
        val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        GlStateManager._bindTexture(texture)
        body()
        GlStateManager._bindTexture(previous)
    }

    private class VideoOutputTexture(textureId: Int) : AbstractTexture() {
        init {
            id = textureId
        }

        override fun load(resourceManager: ResourceManager) = Unit

        override fun releaseId() = Unit

        override fun close() = Unit
    }

    private class ColorUniforms(program: Int = 0) {
        val yOffset = if (program == 0) -1 else GL20.glGetUniformLocation(program, "YOffset")
        val yScale = if (program == 0) -1 else GL20.glGetUniformLocation(program, "YScale")
        val cOffset = if (program == 0) -1 else GL20.glGetUniformLocation(program, "COffset")
        val cScale = if (program == 0) -1 else GL20.glGetUniformLocation(program, "CScale")
        val rv = if (program == 0) -1 else GL20.glGetUniformLocation(program, "RV")
        val gu = if (program == 0) -1 else GL20.glGetUniformLocation(program, "GU")
        val gv = if (program == 0) -1 else GL20.glGetUniformLocation(program, "GV")
        val bu = if (program == 0) -1 else GL20.glGetUniformLocation(program, "BU")
    }

    private companion object {
        val SessionIds = AtomicInteger()
        const val PositionAttribute = 0
        const val UvAttribute = 1
    }
}

private const val VertexShaderSource = """
#version 150

in vec2 Position;
in vec2 UV;

out vec2 texCoord;

void main() {
    texCoord = UV;
    gl_Position = vec4(Position, 0.0, 1.0);
}
"""

private const val FragmentShaderShared = """
uniform float YOffset;
uniform float YScale;
uniform float COffset;
uniform float CScale;
uniform float RV;
uniform float GU;
uniform float GV;
uniform float BU;

in vec2 texCoord;

out vec4 fragColor;

vec4 yuvToRgb(float y, float u, float v) {
    float luma = (y - YOffset) * YScale;
    float cb = (u - COffset) * CScale;
    float cr = (v - COffset) * CScale;
    vec3 rgb = vec3(
        luma + RV * cr,
        luma - GU * cb - GV * cr,
        luma + BU * cb
    );
    return vec4(clamp(rgb, 0.0, 1.0), 1.0);
}
"""

private const val Yuv420FragmentSource = """
#version 150

uniform sampler2D SamplerY;
uniform sampler2D SamplerU;
uniform sampler2D SamplerV;
$FragmentShaderShared
void main() {
    fragColor = yuvToRgb(
        texture(SamplerY, texCoord).r,
        texture(SamplerU, texCoord).r,
        texture(SamplerV, texCoord).r
    );
}
"""

private const val Nv12FragmentSource = """
#version 150

uniform sampler2D SamplerY;
uniform sampler2D SamplerUV;
$FragmentShaderShared
void main() {
    vec2 chroma = texture(SamplerUV, texCoord).rg;
    fragColor = yuvToRgb(texture(SamplerY, texCoord).r, chroma.x, chroma.y);
}
"""
